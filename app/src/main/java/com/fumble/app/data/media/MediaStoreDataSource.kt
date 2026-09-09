package com.fumble.app.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.fumble.app.domain.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A photo that still needs `IS_TRASHED` flipped, paired with the uri to act on. */
data class TrashTarget(val mediaId: Long, val uri: Uri)

/** One image reduced to what dealing and album filtering need. */
data class MediaEntry(val id: Long, val bucketId: Long)

/** The whole library seen through [MediaStoreDataSource.indexImages]. */
data class MediaIndex(
    val entries: List<MediaEntry>,
    val albumNames: Map<Long, String>,
)

/**
 * Outcome of trying to trash a batch directly through [ContentResolver.update].
 *
 * @property trashed We owned these (or already held the grant); they are in the trash now.
 * @property needsConsent The OS refused; the user must approve them via a trash request.
 * @property vanished No longer in MediaStore at all. Nothing left to do, stop tracking them.
 */
data class DirectTrashResult(
    val trashed: List<Long>,
    val needsConsent: List<TrashTarget>,
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
    fun applyTrash(targets: List<TrashTarget>): DirectTrashResult =
        applyFlag(targets, MediaStore.MediaColumns.IS_TRASHED)

    /**
     * Sets `MediaStore.MediaColumns.IS_FAVORITE = 1` on each target, which is what
     * puts a photo into the gallery's own Favourites album.
     *
     * Identical permission story to trashing: silent for owned media, a system dialog
     * for everything else. That is why favourites are queued rather than written the
     * instant a card is swiped up.
     */
    fun applyFavorite(targets: List<TrashTarget>): DirectTrashResult =
        applyFlag(targets, MediaStore.MediaColumns.IS_FAVORITE)

    private fun applyFlag(targets: List<TrashTarget>, column: String): DirectTrashResult {
        val values = ContentValues(1).apply {
            put(column, 1)
        }

        val trashed = mutableListOf<Long>()
        val needsConsent = mutableListOf<TrashTarget>()
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
            try {
                val rows = resolver.update(target.uri, values, null, null)
                when {
                    rows > 0 -> {
                        trashed += target.mediaId
                        consecutiveRefusals = 0
                    }
                    // Zero rows without an exception is ambiguous. If the row is still
                    // there, treat it as a write we are not allowed to make and ask.
                    exists(target.uri) -> needsConsent += target
                    else -> vanished += target.mediaId
                }
            } catch (e: SecurityException) {
                // RecoverableSecurityException (API 29) or a plain SecurityException
                // (API 30+): we do not own this item, so the user has to approve it.
                needsConsent += target
                consecutiveRefusals++
            } catch (e: IllegalArgumentException) {
                // Row or volume is gone, e.g. the SD card was ejected.
                Log.d(TAG, "Dropping unreachable media ${target.mediaId}", e)
                vanished += target.mediaId
            } catch (e: IllegalStateException) {
                Log.d(TAG, "Dropping unreachable media ${target.mediaId}", e)
                vanished += target.mediaId
            }
        }

        return DirectTrashResult(
            trashed = trashed,
            needsConsent = needsConsent,
            vanished = vanished,
        )
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

    /** The favourite equivalent of [trashConsentRequest]: one dialog for the batch. */
    fun favoriteConsentRequest(uris: List<Uri>): IntentSender {
        require(uris.isNotEmpty()) { "favoriteConsentRequest called with no uris" }
        return MediaStore.createFavoriteRequest(resolver, uris, /* value = */ true).intentSender
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
    }
}
