package com.fumble.app.data.repository

import android.util.Log
import androidx.core.net.toUri
import com.fumble.app.data.local.PhotoDecisionDao
import com.fumble.app.data.local.PhotoDecisionEntity
import com.fumble.app.data.local.AppPreferences
import com.fumble.app.data.media.MediaIndex
import com.fumble.app.data.media.MediaStoreDataSource
import com.fumble.app.data.media.FavoritesAlbum
import com.fumble.app.data.media.WriteTarget
import com.fumble.app.di.IoDispatcher
import com.fumble.app.domain.model.Album
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.domain.model.Decision
import com.fumble.app.domain.model.LibraryStats
import com.fumble.app.domain.model.PendingWrite
import com.fumble.app.domain.model.Photo
import com.fumble.app.domain.repository.Applied
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
     * Both queues share the consent machinery, but they differ in what can be trusted.
     * A trash write either lands or throws. A favourite move can report success and
     * quietly do nothing, so favourites are never judged by what MediaStore says, only
     * by where the photos turn out to be — see [flushFavoritesLocked].
     */
    override suspend fun flush(kind: PendingKind): FlushResult = withContext(io) {
        writeLock.withLock {
            when (kind) {
                PendingKind.TRASH -> flushTrashLocked()
                PendingKind.FAVORITE -> flushFavoritesLocked()
            }
        }
    }

    private suspend fun flushTrashLocked(): FlushResult {
        val pending = dao.pendingTrash()
        if (pending.isEmpty()) return FlushResult.Nothing

        val bytesById = pending.associate { it.mediaId to it.sizeBytes }
        val targets = pending.map { WriteTarget(it.mediaId, it.contentUri.toUri()) }

        // Step 1: try the direct write. Free and silent for media this app owns.
        val direct = try {
            mediaStore.applyTrash(targets)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Direct trash write failed", e)
            return FlushResult.Failed(PendingKind.TRASH, e)
        }

        // Photos that vanished from MediaStore are settled too: there is nothing left
        // to write, and leaving them queued would block the batch forever.
        markApplied(PendingKind.TRASH, direct.applied + direct.vanished)

        if (direct.needsConsent.isEmpty()) {
            return FlushResult.Completed(
                kind = PendingKind.TRASH,
                count = direct.applied.size,
                bytes = direct.applied.sumOf { bytesById[it] ?: 0L },
            )
        }

        return requestConsent(PendingKind.TRASH, direct.needsConsent, bytesById)
    }

    /**
     * Gets every queued favourite into [FavoritesAlbum], by whichever route works, and
     * counts only what verifiably arrived.
     *
     * 1. **Look first.** Photos already in the album are done — no write, no dialog.
     * 2. **Copy what cannot be moved.** Photos in another app's media area (WhatsApp's
     *    pictures, for one) cannot be moved out by this app. They are copied instead,
     *    which needs no dialog and leaves them working in the chat they came from.
     * 3. **Move the rest**, directly where allowed, otherwise after one consent dialog.
     * 4. **Look again.** Anything MediaStore claimed to move but did not is copied too.
     */
    private suspend fun flushFavoritesLocked(): FlushResult {
        val pending = dao.pendingFavorites()
        if (pending.isEmpty()) return FlushResult.Nothing

        val located = mediaStore.locate(pending.map { it.mediaId })
        val tally = FavoriteTally()
        val toMove = mutableListOf<WriteTarget>()

        for (row in pending) {
            val path = located[row.mediaId]
            when {
                path == null -> tally.gone += row.mediaId
                FavoritesAlbum.contains(path) -> tally.inAlbum += row.mediaId
                FavoritesAlbum.isUnmovable(path) -> copyInto(tally, row.mediaId)
                else -> toMove += WriteTarget(row.mediaId, row.contentUri.toUri())
            }
        }

        val direct = try {
            mediaStore.applyFavorite(toMove)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Direct favourite move failed", e)
            null
        }
        if (direct != null) verifyMoves(tally, direct.applied + direct.vanished)
        commit(tally)

        val needsConsent = direct?.needsConsent.orEmpty()
        if (needsConsent.isEmpty()) {
            return FlushResult.Completed(
                kind = PendingKind.FAVORITE,
                count = tally.done.count,
                bytes = 0L,
                copies = tally.done.copies,
            )
        }

        return requestConsent(
            kind = PendingKind.FAVORITE,
            targets = needsConsent,
            bytesById = emptyMap(),
            alreadyDone = tally.done,
        )
    }

    /**
     * One system dialog for everything the OS would not let us touch directly.
     *
     * Capped so a queue that grew unusually large — a backlog carried over from an older
     * build, say — cannot hand the platform a dialog with thousands of thumbnails in it.
     * Anything over the cap simply stays queued and is offered on the next flush.
     */
    private fun requestConsent(
        kind: PendingKind,
        targets: List<WriteTarget>,
        bytesById: Map<Long, Long>,
        alreadyDone: Applied = Applied(0),
    ): FlushResult {
        val consentTargets = targets.take(MAX_CONSENT_BATCH)
        val mediaIds = consentTargets.map { it.mediaId }
        val uris = consentTargets.map { it.uri }
        val intentSender = try {
            when (kind) {
                PendingKind.TRASH -> mediaStore.trashConsentRequest(uris)
                PendingKind.FAVORITE -> mediaStore.writeConsentRequest(uris)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not build a $kind request", e)
            return FlushResult.Failed(kind, e)
        }

        return FlushResult.ConsentRequired(
            kind = kind,
            intentSender = intentSender,
            mediaIds = mediaIds,
            bytes = mediaIds.sumOf { bytesById[it] ?: 0L },
            alreadyDone = alreadyDone,
        )
    }

    /**
     * The user approved a consent dialog for [mediaIds].
     *
     * What that means differs per queue, and getting it wrong is silent:
     *
     * - **Trash** — the platform performed the trashing itself before returning, so
     *   there is only bookkeeping left.
     * - **Favourite** — a write request only *grants* access. Nothing has moved yet;
     *   the move is ours to make, now, while the grant is fresh. And even then the
     *   photos are located afterwards, because MediaStore can report a move it did not
     *   make. Whatever still sits outside the album is copied in.
     *
     * @return how many photos verifiably arrived.
     */
    override suspend fun confirmApplied(kind: PendingKind, mediaIds: List<Long>): Applied {
        if (mediaIds.isEmpty()) return Applied(0)
        return withContext(io) {
            when (kind) {
                PendingKind.TRASH -> {
                    markApplied(kind, mediaIds)
                    Applied(mediaIds.size)
                }

                PendingKind.FAVORITE -> writeLock.withLock {
                    val targets = mediaIds.map { WriteTarget(it, mediaStore.contentUri(it)) }
                    try {
                        mediaStore.applyFavorite(targets)
                    } catch (e: RuntimeException) {
                        // Not final: every photo is located below and copied if needed.
                        Log.w(TAG, "Favourite move failed after consent", e)
                    }
                    val tally = FavoriteTally()
                    verifyMoves(tally, mediaIds)
                    commit(tally)
                    tally.done
                }
            }
        }
    }

    override suspend fun repairCopyDates(): Int = withContext(io) {
        // Under the write lock so a flush cannot be creating copies at the same time.
        writeLock.withLock {
            val copies = dao.copies().mapNotNull { row -> row.copyOf?.let { row.mediaId to it } }
            try {
                mediaStore.backdateCopies(copies.toMap())
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not repair copy dates", e)
                0
            }
        }
    }

    /** What happened to each favourite in one pass. */
    private class FavoriteTally {
        val inAlbum = mutableListOf<Long>()
        val gone = mutableListOf<Long>()

        /** Original id to the id of its copy in the album. */
        val copied = mutableListOf<Pair<Long, Long>>()

        val settled: List<Long> get() = inAlbum + gone + copied.map { it.first }
        val done: Applied get() = Applied(count = inAlbum.size + copied.size, copies = copied.size)
    }

    /**
     * Judges attempted moves by where the photos are now, not by what the update
     * returned. MediaStore reports success for moves it silently refuses.
     */
    private fun verifyMoves(tally: FavoriteTally, attempted: List<Long>) {
        if (attempted.isEmpty()) return
        val located = mediaStore.locate(attempted)
        for (id in attempted) {
            val path = located[id]
            when {
                path == null -> tally.gone += id
                FavoritesAlbum.contains(path) -> tally.inAlbum += id
                else -> copyInto(tally, id)
            }
        }
    }

    /** A failed copy leaves the favourite queued, to be tried again next time. */
    private fun copyInto(tally: FavoriteTally, id: Long) {
        mediaStore.copyIntoFavorites(id)?.let { copyId -> tally.copied += id to copyId }
    }

    /**
     * Records the outcome. Copies get a history row of their own: they are new photos
     * with new ids, and without one the deck would deal the user's own favourite back to
     * them as an undecided card.
     */
    private suspend fun commit(tally: FavoriteTally) {
        markApplied(PendingKind.FAVORITE, tally.settled)
        if (tally.copied.isEmpty()) return

        val sizes = dao.byIds(tally.copied.map { it.first }).associate { it.mediaId to it.sizeBytes }
        val now = System.currentTimeMillis()
        tally.copied.forEach { (original, copy) ->
            dao.upsert(
                PhotoDecisionEntity(
                    mediaId = copy,
                    contentUri = mediaStore.contentUri(copy).toString(),
                    decision = Decision.FAVORITE.name,
                    sizeBytes = sizes[original] ?: 0L,
                    decidedAt = now,
                    trashApplied = false,
                    favoriteApplied = true,
                    copyOf = original,
                )
            )
        }
        stateLock.withLock { decidedIdsLocked().addAll(tally.copied.map { it.second }) }
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
