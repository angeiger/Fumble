package com.fumble.app.data.repository

import android.util.Log
import androidx.core.net.toUri
import com.fumble.app.data.local.PhotoDecisionDao
import com.fumble.app.data.local.PhotoDecisionEntity
import com.fumble.app.data.local.AppPreferences
import com.fumble.app.data.media.MediaIndex
import com.fumble.app.data.media.MediaStoreDataSource
import com.fumble.app.data.media.TrashTarget
import com.fumble.app.di.IoDispatcher
import com.fumble.app.domain.model.Album
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.domain.model.Decision
import com.fumble.app.domain.model.LibraryStats
import com.fumble.app.domain.model.PendingWrite
import com.fumble.app.domain.model.Photo
import com.fumble.app.domain.repository.FlushResult
import com.fumble.app.domain.repository.PendingKind
import com.fumble.app.domain.repository.PhotoBatch
import com.fumble.app.domain.repository.PhotoRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Joins the device gallery to the local decision history.
 *
 * The de-duplication contract lives here: [nextBatch] never returns a photo whose
 * `MediaStore._ID` is present in the Room table, and [record] adds an id to that table
 * the instant a card is swiped. Ids are excluded twice over — once when the queue is
 * built, and again for anything decided after that, because a dealt id is already
 * behind the read position.
 */
@Singleton
class PhotoRepositoryImpl @Inject constructor(
    private val mediaStore: MediaStoreDataSource,
    private val dao: PhotoDecisionDao,
    private val prefs: AppPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PhotoRepository {

    /** Guards the deal queue and the decided-id cache below. */
    private val stateLock = Mutex()

    /**
     * Undecided photo ids in shuffled order, built once per session, plus how far into
     * it we have dealt.
     *
     * Shuffling requires knowing the whole candidate set up front, so there is no way
     * to do this with a streaming cursor — `ORDER BY RANDOM()` would re-sample on every
     * page, repeating photos and never signalling exhaustion. Holding the ids costs a
     * `Long` each and buys an exact remaining count for free.
     *
     * Photos added to the device mid-session appear after the next [resetQueue].
     */
    private var queue: List<Long> = emptyList()
    private var dealt = 0
    private var queueReady = false

    /**
     * The library scan behind both the queue and the album list, kept so that opening
     * the album picker does not cost a second pass over MediaStore.
     */
    private var index: MediaIndex? = null

    /**
     * Every already-decided id, loaded once and maintained in memory. Re-reading the
     * table on each refill would be a full scan per ~10 swipes; a `Long` per reviewed
     * photo is a much better trade.
     */
    private var decidedCache: MutableSet<Long>? = null

    /** Serialises flushes so two consent dialogs can never be raised at once. */
    private val writeLock = Mutex()

    override fun observeStats(): Flow<LibraryStats> =
        dao.observeStats().map { projection ->
            LibraryStats(
                kept = projection.kept,
                trashed = projection.trashed,
                favorited = projection.favorited,
                freedBytes = projection.freedBytes,
            )
        }

    override fun observePending(kind: PendingKind): Flow<List<PendingWrite>> {
        val rows = when (kind) {
            PendingKind.TRASH -> dao.observePendingTrash()
            PendingKind.FAVORITE -> dao.observePendingFavorites()
        }
        return rows.map { list ->
            list.map { PendingWrite(mediaId = it.mediaId, sizeBytes = it.sizeBytes) }
        }
    }

    override suspend fun albums(): List<Album> = withContext(io) {
        stateLock.withLock {
            val scan = indexLocked()
            val decided = decidedIdsLocked()

            scan.entries.asSequence()
                .filterNot { it.id in decided }
                .groupingBy { it.bucketId }
                .eachCount()
                .map { (bucketId, count) ->
                    Album(
                        id = bucketId,
                        name = scan.albumNames[bucketId].orEmpty(),
                        photoCount = count,
                    )
                }
                .sortedWith(compareByDescending<Album> { it.photoCount }.thenBy { it.name })
        }
    }

    override suspend fun currentScope(): AlbumScope = withContext(io) {
        stateLock.withLock {
            val scan = indexLocked()
            val albumId = resolvedAlbumIdLocked(scan) ?: return@withLock AlbumScope.All
            AlbumScope.Only(id = albumId, name = scan.albumNames[albumId].orEmpty())
        }
    }

    override suspend fun selectAlbum(albumId: Long?) {
        withContext(io) {
            stateLock.withLock {
                prefs.selectedAlbumId = albumId
                // The library itself did not change, only which part of it is dealt,
                // so the scan is worth keeping.
                discardQueueLocked(keepIndex = true)
            }
        }
    }

    override suspend fun nextBatch(count: Int): PhotoBatch = withContext(io) {
        stateLock.withLock {
            buildQueueIfNeededLocked()

            val batch = ArrayList<Photo>(count)
            // Ids can resolve to nothing if the photo was deleted outside the app since
            // the queue was built, so keep taking until the hand is full or we run out.
            while (batch.size < count && dealt < queue.size) {
                val take = (count - batch.size).coerceIn(MIN_CHUNK, MAX_CHUNK)
                val end = minOf(dealt + take, queue.size)
                val chunk = queue.subList(dealt, end)
                dealt = end
                batch += mediaStore.photosByIds(chunk)
            }

            PhotoBatch(photos = batch, remainingUndealt = queue.size - dealt)
        }
    }

    override suspend fun record(photo: Photo, decision: Decision) {
        withContext(io) {
            dao.upsert(
                PhotoDecisionEntity(
                    mediaId = photo.id,
                    contentUri = photo.uri.toString(),
                    decision = decision.name,
                    sizeBytes = photo.sizeBytes,
                    decidedAt = System.currentTimeMillis(),
                    trashApplied = false,
                    favoriteApplied = false,
                )
            )
            stateLock.withLock { decidedIdsLocked().add(photo.id) }
        }
    }

    override suspend fun forget(photo: Photo) {
        withContext(io) {
            dao.forget(photo.id)
            stateLock.withLock { decidedCache?.remove(photo.id) }
        }
    }

    /**
     * Settles one queue.
     *
     * Trashing and favouriting are the same shape of problem — a MediaStore flag the
     * app may write for its own media and must ask about for everyone else's — so they
     * share one path rather than two that would drift apart.
     */
    override suspend fun flush(kind: PendingKind): FlushResult = withContext(io) {
        writeLock.withLock {
            val pending = when (kind) {
                PendingKind.TRASH -> dao.pendingTrash()
                PendingKind.FAVORITE -> dao.pendingFavorites()
            }
            if (pending.isEmpty()) return@withLock FlushResult.Nothing

            val bytesById = pending.associate { it.mediaId to it.sizeBytes }
            val targets = pending.map { TrashTarget(it.mediaId, it.contentUri.toUri()) }

            // Step 1: try the direct write. Free and silent for media this app owns.
            val direct = try {
                when (kind) {
                    PendingKind.TRASH -> mediaStore.applyTrash(targets)
                    PendingKind.FAVORITE -> mediaStore.applyFavorite(targets)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Direct $kind write failed", e)
                return@withLock FlushResult.Failed(kind, e)
            }

            // Photos that vanished from MediaStore are settled too: there is nothing
            // left to write, and leaving them queued would block the batch forever.
            markApplied(kind, direct.trashed + direct.vanished)

            if (direct.needsConsent.isEmpty()) {
                return@withLock FlushResult.Completed(
                    kind = kind,
                    count = direct.trashed.size,
                    bytes = direct.trashed.sumOf { bytesById[it] ?: 0L },
                )
            }

            // Step 2: one system dialog for everything the OS would not let us touch.
            //
            // Capped so a queue that grew unusually large — a backlog carried over from
            // an older build, say — cannot hand the platform a dialog with thousands of
            // thumbnails in it. Anything over the cap simply stays queued and is offered
            // on the next flush. Normal operation never comes near this.
            val consentTargets = direct.needsConsent.take(MAX_CONSENT_BATCH)
            val mediaIds = consentTargets.map { it.mediaId }
            val uris = consentTargets.map { it.uri }
            val intentSender = try {
                when (kind) {
                    PendingKind.TRASH -> mediaStore.trashConsentRequest(uris)
                    PendingKind.FAVORITE -> mediaStore.favoriteConsentRequest(uris)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not build a $kind request", e)
                return@withLock FlushResult.Failed(kind, e)
            }

            FlushResult.ConsentRequired(
                kind = kind,
                intentSender = intentSender,
                mediaIds = mediaIds,
                bytes = mediaIds.sumOf { bytesById[it] ?: 0L },
            )
        }
    }

    override suspend fun confirmApplied(kind: PendingKind, mediaIds: List<Long>) {
        if (mediaIds.isEmpty()) return
        withContext(io) { markApplied(kind, mediaIds) }
    }

    private suspend fun markApplied(kind: PendingKind, mediaIds: List<Long>) {
        if (mediaIds.isEmpty()) return
        when (kind) {
            PendingKind.TRASH -> dao.markTrashApplied(mediaIds)
            PendingKind.FAVORITE -> dao.markFavoriteApplied(mediaIds)
        }
    }

    override suspend fun resetHistory() {
        withContext(io) {
            dao.clear()
            stateLock.withLock {
                decidedCache = null
                discardQueueLocked()
            }
        }
    }

    override suspend fun resetQueue() {
        stateLock.withLock { discardQueueLocked() }
    }

    /**
     * Builds the shuffled deal queue: every image in the selected album, minus
     * everything the user has already ruled on. Caller must hold [stateLock].
     */
    private suspend fun buildQueueIfNeededLocked() {
        if (queueReady) return
        val scan = indexLocked()
        val decided = decidedIdsLocked()
        val albumId = resolvedAlbumIdLocked(scan)

        queue = scan.entries.asSequence()
            .filter { albumId == null || it.bucketId == albumId }
            .filterNot { it.id in decided }
            .map { it.id }
            .toList()
            .shuffled()

        dealt = 0
        queueReady = true
    }

    private fun discardQueueLocked(keepIndex: Boolean = false) {
        queue = emptyList()
        dealt = 0
        queueReady = false
        if (!keepIndex) index = null
    }

    private fun indexLocked(): MediaIndex =
        index ?: mediaStore.indexImages().also { index = it }

    /**
     * The stored album id, or `null` if it no longer exists.
     *
     * Albums disappear when their last photo does. Holding on to a dead id would leave
     * the user staring at "all caught up" for a folder that is simply gone, so the
     * selection is dropped rather than kept.
     */
    private fun resolvedAlbumIdLocked(scan: MediaIndex): Long? {
        val stored = prefs.selectedAlbumId ?: return null
        if (stored in scan.albumNames) return stored
        prefs.selectedAlbumId = null
        return null
    }

    /** Caller must hold [stateLock]; [Mutex] is not reentrant. */
    private suspend fun decidedIdsLocked(): MutableSet<Long> =
        decidedCache ?: dao.allDecidedIds().toHashSet().also { decidedCache = it }

    private companion object {
        const val TAG = "PhotoRepository"

        /** Bounds on how many ids are resolved per MediaStore query while dealing. */
        const val MIN_CHUNK = 8
        const val MAX_CHUNK = 100

        /** Most photos ever put in front of the user in a single consent dialog. */
        const val MAX_CONSENT_BATCH = 200
    }
}
