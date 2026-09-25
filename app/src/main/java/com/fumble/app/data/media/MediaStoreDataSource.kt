package com.fumble.app.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.fumble.app.domain.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A photo awaiting a deferred MediaStore write, paired with the uri to act on. */
data class WriteTarget(val mediaId: Long, val uri: Uri)

/** One image reduced to what dealing and album filtering need. */
data class MediaEntry(val id: Long, val bucketId: Long)

/** The whole library seen through [MediaStoreDataSource.indexImages]. */
data class MediaIndex(
    val entries: List<MediaEntry>,
    val albumNames: Map<Long, String>,
)

/**
 * Outcome of trying to write a batch directly through [ContentResolver.update].
 *
 * @property applied The write landed — we owned these, or already held the grant.
 * @property needsConsent The OS refused; the user must approve them first.
 * @property vanished No longer in MediaStore at all. Nothing left to do, stop tracking them.
 */
data class DirectWriteResult(
    val applied: List<Long>,
    val needsConsent: List<WriteTarget>,
    val vanished: List<Long>,
)

/**
 * Every read from and write to the device gallery goes through here.
 *
 * All methods block; callers are responsible for moving to an IO dispatcher.
 */
@Singleton
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * `VOLUME_EXTERNAL` spans every currently mounted external volume, so photos on
     * removable storage are included too.
     */
    private val collection: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * New files need a concrete volume; `VOLUME_EXTERNAL` spans several and is rejected
     * for inserts. Copies land on the device's own storage.
     */
    private val insertCollection: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )

    /**
     * Every non-trashed image, reduced to an id and the album it sits in.
     *
     * Two columns over the whole library, which is cheap enough to do once per session:
     * a few tens of thousands of rows come back in well under a second, and sixteen
     * bytes each is nothing to hold on to. The repository shuffles this list and deals
     * from it, which is what makes a random-order deck possible at all — you cannot
     * page randomly through a table without first knowing what is in it. The same scan
     * also yields the album list, so opening the album picker costs no extra query.
     *
     * Album names are kept in a separate map rather than on every entry: there are a
     * handful of albums and tens of thousands of photos, and a string per row would
     * dwarf everything else here.
     */
    fun indexImages(): MediaIndex {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
        }
        val indexProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )

        val entries = mutableListOf<MediaEntry>()
        val names = HashMap<Long, String>()

        resolver.query(collection, indexProjection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                // Loose media with no parent folder does exist; it gets its own bucket
                // so it stays reachable instead of vanishing from every album.
                val bucketId =
                    if (cursor.isNull(bucketColumn)) UNKNOWN_BUCKET_ID
                    else cursor.getLong(bucketColumn)

                entries += MediaEntry(id = cursor.getLong(idColumn), bucketId = bucketId)
                if (bucketId !in names) {
                    names[bucketId] = cursor.getStringOrNull(nameColumn).orEmpty()
                }
            }
        }

        return MediaIndex(entries = entries, albumNames = names)
    }

    /**
     * Full rows for the given ids, **in the order they were asked for**.
     *
     * SQLite returns `IN (…)` matches in its own order, so the result is re-sorted
     * against [ids] here — otherwise the caller's shuffle would be undone by the query.
     * Ids that no longer resolve (deleted or trashed elsewhere in the meantime) are
     * dropped silently.
     */
    fun photosByIds(ids: List<Long>): List<Photo> {
        if (ids.isEmpty()) return emptyList()

        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media._ID} IN (${ids.joinToString(",") { "?" }})",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                Array(ids.size) { ids[it].toString() },
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
        }

        val found = HashMap<Long, Photo>(ids.size)
        resolver.query(collection, projection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val bucketColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                found[id] = Photo(
                    id = id,
                    uri = contentUri(id),
                    displayName = cursor.getStringOrEmpty(nameColumn),
                    sizeBytes = cursor.getLong(sizeColumn),
                    dateAddedSeconds = cursor.getLong(dateColumn),
                    bucketName = cursor.getStringOrNull(bucketColumn),
                )
            }
        }

        return ids.mapNotNull(found::get)
    }

    /**
     * Sets `MediaStore.MediaColumns.IS_TRASHED = 1` on each target.
     *
     * This succeeds without any dialog for media this app owns. For everything else —
     * which in practice is almost every photo, since the camera app owns them — the
     * platform throws and the caller has to fall back to [trashConsentRequest].
     */
    fun applyTrash(targets: List<WriteTarget>): DirectWriteResult =
        applyValues(
            targets = targets,
            values = ContentValues(1).apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) },
        )

    /**
     * Asks MediaStore to move each target into [FavoritesAlbum] and mark it
     * `IS_FAVORITE`.
     *
     * **The result says only what was attempted, never what happened.** MediaStore can
     * report a successful update while quietly leaving the file where it was — it does
     * exactly that for photos in another app's media area, having first created the
     * empty target folder. Callers must [locate] the photos afterwards and trust only
     * that.
     *
     * Why a move at all: Android's own favourite flag was the first design, and Google
     * Photos, the gallery most Android users open, does not show it. A folder is
     * something every gallery shows. The flag is still set alongside, for the apps that
     * do honour it. Moving rewrites `RELATIVE_PATH` on the existing row, so the photo
     * keeps its MediaStore id and nothing is duplicated.
     */
    fun applyFavorite(targets: List<WriteTarget>): DirectWriteResult =
        applyValues(
            targets = targets,
            values = ContentValues(2).apply {
                put(MediaStore.MediaColumns.IS_FAVORITE, 1)
                put(MediaStore.MediaColumns.RELATIVE_PATH, FavoritesAlbum.RELATIVE_PATH)
            },
        )

    /**
     * Where each photo actually lives right now, as its `RELATIVE_PATH`. Photos that no
     * longer exist are absent from the result.
     */
    fun locate(ids: List<Long>): Map<Long, String> {
        val located = HashMap<Long, String>(ids.size)
        // Well under SQLite's bound-parameter limit per query.
        ids.chunked(LOCATE_CHUNK).forEach { chunk ->
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    Array(chunk.size) { chunk[it].toString() },
                )
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            resolver.query(collection, projection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pathColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    located[cursor.getLong(idColumn)] = cursor.getStringOrEmpty(pathColumn)
                }
            }
        }
        return located
    }

    /**
     * Copies a photo into [FavoritesAlbum], for photos Android will not let this app
     * move. Needs only read access, so no consent dialog. The copy belongs to this app.
     *
     * The copy must carry the original's date, or it sorts into galleries as a photo
     * from today. Android re-derives the date from the file when the copy is published
     * and ignores the `DATE_TAKEN` passed in (see [CopyDates]), so the date goes into
     * the file itself: into the EXIF data, with its time-zone offset, and into the
     * file's modification time, set while the copy is still pending.
     *
     * @return the copy's MediaStore id, or `null` if the copy could not be made. A
     *   half-written copy is removed rather than left behind.
     */
    fun copyIntoFavorites(sourceId: Long): Long? {
        val source = contentUri(sourceId)
        val meta = readCopyMeta(source) ?: return null

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, meta.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, meta.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FavoritesAlbum.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = try {
            resolver.insert(insertCollection, values)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not create a copy of $sourceId", e)
            null
        } ?: return null

        return try {
            val input = resolver.openInputStream(source)
                ?: throw IOException("Cannot read $source")
            input.use { from ->
                val output = resolver.openOutputStream(target)
                    ?: throw IOException("Cannot write $target")
                output.use { to -> from.copyTo(to) }
            }
            stampDate(target, meta.takenMillis)
            // Last before publishing: writing the EXIF data above touches the file time.
            backdateFile(target, meta.takenMillis)
            // Publishing scans the file, and the scan sets the dates from it.
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            ContentUris.parseId(target)
        } catch (e: IOException) {
            discard(target, sourceId, e)
        } catch (e: RuntimeException) {
            discard(target, sourceId, e)
        }
    }

    private class CopyMeta(val displayName: String, val mimeType: String?, val takenMillis: Long)

    private fun readCopyMeta(source: Uri): CopyMeta? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        return try {
            resolver.query(source, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val taken = cursor.getLong(2)
                val modifiedSeconds = cursor.getLong(3)
                val addedSeconds = cursor.getLong(4)
                // For a received picture, the file's own timestamp is when it arrived —
                // the closest thing to a date it has.
                val takenMillis = when {
                    taken > 0 -> taken
                    modifiedSeconds > 0 -> modifiedSeconds * 1000
                    else -> addedSeconds * 1000
                }
                CopyMeta(
                    displayName = cursor.getStringOrNull(0) ?: "fumble_$takenMillis.jpg",
                    mimeType = cursor.getStringOrNull(1),
                    takenMillis = takenMillis,
                )
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read $source", e)
            null
        }
    }

    /**
     * Writes [takenMillis] into the EXIF data of [target], with the time-zone offset
     * that makes Android accept it as the capture date.
     *
     * A date the file already carries is left alone, unless it is one this app wrote
     * earlier without an offset — copies from 4.1.1. Only for those is the zone known
     * for certain; guessing one for a date written elsewhere could shift it by hours.
     *
     * Best effort: a copy with the wrong date is still better than no copy.
     */
    private fun stampDate(target: Uri, takenMillis: Long) {
        try {
            resolver.openFileDescriptor(target, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                val zone = TimeZone.getDefault()
                val stamp = CopyDates.exifDateTime(takenMillis, zone)
                val existing = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                val hasOffset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) != null
                if (existing != null && (existing != stamp || hasOffset)) return

                val offset = CopyDates.exifOffset(takenMillis, zone)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
                exif.saveAttributes()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Could not stamp a date on $target", e)
        } catch (e: RuntimeException) {
            // Formats ExifInterface cannot write, e.g. some PNGs.
            Log.d(TAG, "Could not stamp a date on $target", e)
        }
    }

    /**
     * Sets the modification time of the file behind [target], which only works on files
     * this app owns — its copies.
     *
     * @return the file's path if the time was set, for rescanning; otherwise `null`.
     */
    private fun backdateFile(target: Uri, takenMillis: Long): String? {
        val path = try {
            resolver.query(target, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getStringOrNull(0) else null }
        } catch (e: RuntimeException) {
            Log.d(TAG, "Could not find the file behind $target", e)
            null
        } ?: return null
        return if (File(path).setLastModified(takenMillis)) {
            path
        } else {
            Log.d(TAG, "Could not set the file time of $path")
            null
        }
    }

    /**
     * Gives earlier copies their original date after all.
     *
     * Copies made before 4.1.2 have their date only in the EXIF data, without an offset,
     * and a file time from the day they were copied — so Android shows them as new.
     * They belong to this app, so their file can still be fixed and rescanned. Copies
     * that already carry their date are skipped, which makes this cheap to run on
     * every start.
     *
     * @param copies each copy's id mapped to the id of its original.
     * @return how many copies verifiably carry their date now.
     */
    fun backdateCopies(copies: Map<Long, Long>): Int {
        if (copies.isEmpty()) return 0
        val fixed = mutableMapOf<Long, Long>()
        val paths = mutableListOf<String>()

        for ((copyId, dates) in fileDates(copies.keys.toList())) {
            // Fine already: Android knows a capture date, and the file time agrees.
            if (dates.takenMillis > 0 &&
                !CopyDates.needsBackdating(dates.modifiedSeconds, dates.takenMillis)
            ) {
                continue
            }
            val copy = contentUri(copyId)
            val takenMillis = readCopyMeta(contentUri(copies.getValue(copyId)))?.takenMillis
                ?: readExifDate(copy)
                ?: continue
            if (!CopyDates.needsBackdating(dates.modifiedSeconds, takenMillis)) continue

            stampDate(copy, takenMillis)
            val path = backdateFile(copy, takenMillis) ?: continue
            fixed[copyId] = takenMillis
            paths += path
        }
        if (paths.isEmpty()) return 0

        rescan(paths)
        return fileDates(fixed.keys.toList()).count { (copyId, dates) ->
            !CopyDates.needsBackdating(dates.modifiedSeconds, fixed.getValue(copyId))
        }
    }

    /** The two dates Android keeps for a file, each in the unit MediaStore uses. */
    private class FileDates(val takenMillis: Long, val modifiedSeconds: Long)

    private fun fileDates(ids: List<Long>): Map<Long, FileDates> {
        val dates = HashMap<Long, FileDates>(ids.size)
        ids.chunked(LOCATE_CHUNK).forEach { chunk ->
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    Array(chunk.size) { chunk[it].toString() },
                )
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            try {
                resolver.query(collection, projection, args, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        // A missing DATE_TAKEN reads as 0: no capture date known.
                        dates[cursor.getLong(0)] = FileDates(cursor.getLong(1), cursor.getLong(2))
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not read file dates", e)
            }
        }
        return dates
    }

    /** The date written into a copy, for when its original is gone. */
    private fun readExifDate(target: Uri): Long? =
        try {
            resolver.openFileDescriptor(target, "r")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                CopyDates.parseExif(
                    dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                    zone = TimeZone.getDefault(),
                )
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }

    /** Has Android read [paths] again, and waits until it has. */
    private fun rescan(paths: List<String>) {
        val done = CountDownLatch(paths.size)
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
            done.countDown()
        }
        if (!done.await(RESCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "Rescan of ${paths.size} copies did not finish in time")
        }
    }

    private fun discard(target: Uri, sourceId: Long, cause: Exception): Long? {
        Log.w(TAG, "Copying $sourceId failed", cause)
        try {
            resolver.delete(target, null, null)
        } catch (e: RuntimeException) {
            Log.d(TAG, "Could not remove the partial copy $target", e)
        }
        return null
    }

    private fun applyValues(
        targets: List<WriteTarget>,
        values: ContentValues,
    ): DirectWriteResult {
        val applied = mutableListOf<Long>()
        val needsConsent = mutableListOf<WriteTarget>()
        val vanished = mutableListOf<Long>()

        // Each refused write costs a binder round trip, and a gallery is overwhelmingly
        // made of photos this app does not own. Once a run of them has been refused,
        // stop probing and send the rest straight to the consent dialog — they end up
        // on the same path anyway, just without the wasted calls. Matters at batch
        // sizes in the hundreds, where probing every item delays the dialog visibly.
        var consecutiveRefusals = 0

        for (target in targets) {
            if (consecutiveRefusals >= DIRECT_WRITE_GIVE_UP_AFTER) {
                needsConsent += target
                continue
            }
            when (write(target.uri, values)) {
                WriteOutcome.APPLIED -> {
                    applied += target.mediaId
                    consecutiveRefusals = 0
                }

                WriteOutcome.REFUSED -> {
                    needsConsent += target
                    consecutiveRefusals++
                }

                WriteOutcome.GONE -> vanished += target.mediaId
            }
        }

        return DirectWriteResult(
            applied = applied,
            needsConsent = needsConsent,
            vanished = vanished,
        )
    }

    private enum class WriteOutcome { APPLIED, REFUSED, GONE }

    private fun write(uri: Uri, values: ContentValues): WriteOutcome =
        try {
            val rows = resolver.update(uri, values, null, null)
            when {
                rows > 0 -> WriteOutcome.APPLIED
                // Zero rows without an exception is ambiguous. If the row is still
                // there, treat it as a write we are not allowed to make and ask.
                exists(uri) -> WriteOutcome.REFUSED
                else -> WriteOutcome.GONE
            }
        } catch (e: SecurityException) {
            // RecoverableSecurityException (API 29) or a plain SecurityException
            // (API 30+): we do not own this item, so the user has to approve it.
            WriteOutcome.REFUSED
        } catch (e: IllegalArgumentException) {
            unreachable(uri, e)
        } catch (e: IllegalStateException) {
            unreachable(uri, e)
        }

    /**
     * The write failed for a reason other than permission, e.g. the SD card was ejected.
     * For favourites this is not final — the caller locates the photo afterwards and
     * copies it if it still exists.
     */
    private fun unreachable(uri: Uri, cause: Exception): WriteOutcome {
        Log.d(TAG, "Write failed for $uri", cause)
        return WriteOutcome.GONE
    }

    /**
     * A single system dialog covering [uris]. The user approves the whole batch at once,
     * and on approval the platform does the trashing itself — there is no second write.
     *
     * Trashed items are purged automatically by Android after 30 days.
     */
    fun trashConsentRequest(uris: List<Uri>): IntentSender {
        require(uris.isNotEmpty()) { "trashConsentRequest called with no uris" }
        return MediaStore.createTrashRequest(resolver, uris, /* value = */ true).intentSender
    }

    /**
     * One system dialog granting write access to [uris].
     *
     * Unlike [trashConsentRequest], approval changes nothing by itself. It only grants
     * access; the caller must then perform the write — here, moving the photos — while
     * the grant is fresh.
     */
    fun writeConsentRequest(uris: List<Uri>): IntentSender {
        require(uris.isNotEmpty()) { "writeConsentRequest called with no uris" }
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    fun contentUri(mediaId: Long): Uri = ContentUris.withAppendedId(collection, mediaId)

    /** Trashed rows are matched too, so an already-trashed item does not read as missing. */
    private fun exists(uri: Uri): Boolean {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), args, null)
                ?.use { it.count > 0 }
                ?: false
        } catch (e: SecurityException) {
            // We cannot read it, but it clearly exists.
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getStringOrEmpty(index: Int): String =
        getStringOrNull(index).orEmpty()

    companion object {
        private const val TAG = "MediaStoreDataSource"

        /** Refused direct writes in a row before [applyTrash] stops probing. */
        private const val DIRECT_WRITE_GIVE_UP_AFTER = 5

        /** Bucket assigned to images MediaStore reports without a parent folder. */
        const val UNKNOWN_BUCKET_ID = Long.MIN_VALUE

        private const val LOCATE_CHUNK = 400

        /** Generous: a scan takes milliseconds per file, but must never hang a flush. */
        private const val RESCAN_TIMEOUT_SECONDS = 30L
    }
}
