package com.fumble.app.domain.repository

import android.content.IntentSender
import com.fumble.app.domain.model.Album
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.domain.model.Decision
import com.fumble.app.domain.model.LibraryStats
import com.fumble.app.domain.model.PendingWrite
import com.fumble.app.domain.model.Photo
import kotlinx.coroutines.flow.Flow

/**
 * The two MediaStore writes this app defers.
 *
 * Both need the user's blessing for photos the app does not own, and both are batched
 * for exactly that reason — asking once per swipe would make the app unusable.
 */
enum class PendingKind {
    TRASH,
    FAVORITE,
}

/** What came of trying to settle one of the queues. */
sealed interface FlushResult {

    /** Queue was empty. */
    data object Nothing : FlushResult

    /**
     * Everything queued is written, no dialog needed.
     *
     * @property count photos confirmed done — for favourites, confirmed *in the album*.
     * @property copies how many of [count] are copies rather than moves.
     */
    data class Completed(
        val kind: PendingKind,
        val count: Int,
        val bytes: Long,
        val copies: Int = 0,
    ) : FlushResult

    /**
     * The platform wants the user to approve these first. Launch [intentSender]; on
     * approval call [PhotoRepository.confirmApplied]. On refusal do nothing — the rows
     * stay queued and the next flush offers them again.
     */
    data class ConsentRequired(
        val kind: PendingKind,
        val intentSender: IntentSender,
        val mediaIds: List<Long>,
        val bytes: Long,
        /**
         * Favourites already settled in this pass before the dialog was needed — ones
         * found in the album, or copied because they could not be moved. Reported
         * together with whatever the dialog brings.
         */
        val alreadyDone: Applied = Applied(0),
    ) : FlushResult

    /** The queue could not be reached at all this time. */
    data class Failed(val kind: PendingKind, val cause: Throwable) : FlushResult
}

/**
 * How many photos a write verifiably reached.
 *
 * @property copies how many of [count] are copies in the favourites album rather than
 *   moves, because Android would not let the original leave its folder.
 */
data class Applied(val count: Int, val copies: Int = 0) {
    operator fun plus(other: Applied) = Applied(count + other.count, copies + other.copies)
}

/**
 * A hand of cards plus how many undecided photos are still behind them.
 *
 * [remainingUndealt] is exact rather than estimated: it is simply what is left of the
 * shuffled queue, so the "N left" readout cannot drift from reality.
 */
data class PhotoBatch(
    val photos: List<Photo>,
    val remainingUndealt: Int,
)

interface PhotoRepository {

    fun observeStats(): Flow<LibraryStats>

    /** The queue of swipes of [kind] whose MediaStore write has not landed yet. */
    fun observePending(kind: PendingKind): Flow<List<PendingWrite>>

    /**
     * Albums that still hold undecided photos, largest first.
     *
     * Empty albums are left out: offering a folder that would immediately say "all
     * caught up" is only a way to waste a tap.
     */
    suspend fun albums(): List<Album>

    /** Which album the deck is dealing from. Survives restarts. */
    suspend fun currentScope(): AlbumScope

    /**
     * Narrows the deck to one album, or to the whole library with `null`.
     *
     * Discards the queue so the next [nextBatch] deals from the new scope. The decision
     * history is untouched — a photo ruled on while viewing everything stays ruled on
     * inside its album.
     */
    suspend fun selectAlbum(albumId: Long?)

    /**
     * The next [count] photos the user has not ruled on yet, in random order, within
     * the current [currentScope].
     *
     * Deals from a queue of undecided ids that is built and shuffled once per session.
     * An empty [PhotoBatch.photos] means the queue is spent and there is nothing left
     * to review.
     */
    suspend fun nextBatch(count: Int): PhotoBatch

    /** Writes the swipe to the history so this photo is never dealt again. */
    suspend fun record(photo: Photo, decision: Decision)

    /** Undo: drops the history row so the photo returns to the pool. */
    suspend fun forget(photo: Photo)

    /** Attempts the queued writes of [kind]. Safe to call when the queue is empty. */
    suspend fun flush(kind: PendingKind): FlushResult

    /**
     * The user approved a [FlushResult.ConsentRequired] batch. For favourites this is
     * where the photos are actually moved, since approval only grants access — and
     * where any the system still refuses to move are copied instead.
     *
     * @return how many photos verifiably arrived. Any shortfall stays queued.
     */
    suspend fun confirmApplied(kind: PendingKind, mediaIds: List<Long>): Applied

    /**
     * Gives album copies made by earlier versions their original date, so galleries
     * stop showing them as new. Silent, and cheap once there is nothing left to fix.
     *
     * @return how many copies were fixed.
     */
    suspend fun repairCopyDates(): Int

    /** Clears the whole decision history so every photo can be reviewed again. */
    suspend fun resetHistory()

    /**
     * Discards the current queue so the next [nextBatch] rebuilds and reshuffles it.
     *
     * Needed whenever the visible set of photos may have changed underneath the app —
     * a re-scoped access grant, or a fresh session.
     */
    suspend fun resetQueue()
}
