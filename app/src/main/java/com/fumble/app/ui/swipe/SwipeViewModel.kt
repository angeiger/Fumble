package com.fumble.app.ui.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fumble.app.data.local.AppPreferences
import com.fumble.app.domain.model.Album
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.domain.model.Photo
import com.fumble.app.domain.model.SwipeDirection
import com.fumble.app.domain.repository.FlushResult
import com.fumble.app.domain.repository.PendingKind
import com.fumble.app.domain.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SwipeViewModel @Inject constructor(
    private val repository: PhotoRepository,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val deckState = MutableStateFlow(DeckState())

    private val _effects = Channel<SwipeEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val settings = combine(
        preferences.trashThreshold,
        preferences.paletteId,
    ) { threshold, paletteId -> threshold to paletteId }

    private val queues = combine(
        repository.observePending(PendingKind.TRASH),
        repository.observePending(PendingKind.FAVORITE),
    ) { trash, favorites -> trash to favorites }

    val uiState: StateFlow<SwipeUiState> = combine(
        deckState,
        repository.observeStats(),
        queues,
        settings,
    ) { deck, stats, (pendingTrash, pendingFavorites), (threshold, paletteId) ->
        SwipeUiState(
            loading = deck.loading,
            deck = deck.deck,
            // Exact, not estimated: what is left in the shuffled queue plus what is
            // already on the table in front of the user.
            remaining = deck.undealt + deck.deck.size,
            scope = deck.scope,
            albums = deck.albums,
            keptCount = stats.kept,
            trashedCount = stats.trashed,
            favoritedCount = stats.favorited,
            freedBytes = stats.freedBytes,
            pendingTrashCount = pendingTrash.size,
            pendingTrashBytes = pendingTrash.sumOf { it.sizeBytes },
            pendingFavoriteCount = pendingFavorites.size,
            canUndo = deck.undoStack.isNotEmpty(),
            undoDepth = deck.undoStack.size,
            exhausted = deck.exhausted,
            trashThreshold = threshold,
            paletteId = paletteId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SwipeUiState(),
    )

    private var refillJob: Job? = null
    private var backlogJob: Job? = null
    private var started = false

    private val promptPolicy = TrashPromptPolicy()

    /** True while a system trash dialog is on screen, so we never stack two. */
    private var consentInFlight = false

    /** Ids handed to the system dialog, kept here so an activity restart cannot lose them. */
    private var awaitingConsentIds: List<Long> = emptyList()

    /** Which queue the open dialog belongs to, so the answer is applied to the right one. */
    private var awaitingConsentKind: PendingKind? = null

    /** True between entering a flush and knowing its outcome. */
    private var flushing = false

    /** Called once photo access is available. Safe to call repeatedly. */
    fun onAccessAvailable() {
        if (started) return
        started = true
        watchBacklog()
        reload()
    }

    /**
     * Watches the persisted trash queue and asks [TrashPromptPolicy] whether it is time
     * to empty it.
     *
     * Driving this from the database rather than from a swipe counter is what makes a
     * backlog inherited from an earlier session visible at all — see the note on
     * [TrashPromptPolicy].
     */
    private fun watchBacklog() {
        if (backlogJob != null) return
        backlogJob = viewModelScope.launch {
            combine(
                repository.observePending(PendingKind.TRASH)
                    .map { it.size }
                    .distinctUntilChanged(),
                preferences.trashThreshold,
            ) { pending, threshold -> pending to threshold }
                .distinctUntilChanged()
                .collect { (pending, threshold) ->
                    // Watched together on purpose: lowering the threshold below what is
                    // already queued has to prompt straight away, not wait for the next
                    // swipe to nudge the queue.
                    promptPolicy.threshold = threshold
                    if (promptPolicy.shouldPrompt(pending)) settleQueues(userInitiated = false)
                }
        }
    }

    fun onTrashThresholdChange(value: Int) = preferences.setTrashThreshold(value)

    fun onPaletteChange(id: String) = preferences.setPaletteId(id)

    /**
     * The set of photos this app can see changed — the user picked a different
     * selection under Android 14's partial access. Everything gets re-dealt.
     */
    fun onPhotoAccessChanged() {
        started = true
        watchBacklog()
        reload()
    }

    fun onSwipe(photo: Photo, direction: SwipeDirection) {
        val decision = direction.decision

        // Remove the card first so the UI never waits on IO.
        deckState.update { current ->
            current.copy(
                deck = current.deck.filterNot { it.id == photo.id },
                undoStack = current.undoStack + photo,
            )
        }

        viewModelScope.launch {
            repository.record(photo, decision)
            refillIfNeeded()
        }
        // No prompt is triggered from here: [watchBacklog] reacts to the queue itself,
        // which is the only count that survives the process dying.
    }

    /**
     * Takes back the most recent swipe, and can keep going all the way to the last
     * flush.
     *
     * The stack holds both directions, because "undo my last swipe" should not care
     * which way it went. It is emptied the moment photos actually reach the system
     * trash — past that point this app no longer decides their fate, and offering undo
     * would be a lie.
     */
    fun onUndo() {
        val target = deckState.value.undoStack.lastOrNull() ?: return
        deckState.update { current ->
            current.copy(
                deck = listOf(target) + current.deck,
                undoStack = current.undoStack.dropLast(1),
            )
        }
        viewModelScope.launch { repository.forget(target) }
    }

    /** User tapped the pending-work pill. */
    fun onEmptyTrashRequested() {
        viewModelScope.launch { settleQueues(userInitiated = true) }
    }

    /**
     * The album picker was opened. Counts are recomputed from the cached library scan
     * rather than read once at startup, so they stay honest as albums get worked down.
     */
    fun onAlbumPickerOpened() {
        viewModelScope.launch {
            val albums = repository.albums()
            deckState.update { it.copy(albums = albums) }
        }
    }

    /**
     * Narrows the deck to one album, or to everything with `null`.
     *
     * The decision history is untouched, so a photo already ruled on does not come back
     * just because it is being looked at through a different album. The undo stack does
     * not survive the switch: the queue it referred to is gone.
     */
    fun onAlbumSelected(albumId: Long?) {
        viewModelScope.launch {
            repository.selectAlbum(albumId)
            // selectAlbum already discarded the queue; no need to rescan the library.
            reload(rescan = false)
        }
    }

    /**
     * A system consent dialog closed.
     *
     * The ids under review are held here rather than in the composable on purpose: the
     * dialog is a separate activity, and ours can be recreated behind it. State
     * remembered in composition would come back empty, and the rows would be written by
     * the platform while this app still believed they were queued.
     */
    fun onConsentResult(approved: Boolean) {
        consentInFlight = false
        val kind = awaitingConsentKind
        val mediaIds = awaitingConsentIds
        awaitingConsentKind = null
        awaitingConsentIds = emptyList()
        if (kind == null) return

        viewModelScope.launch {
            if (approved) {
                repository.confirmApplied(kind, mediaIds)

                if (kind == PendingKind.TRASH) {
                    promptPolicy.onSettled()
                    val freed = deckState.value.lastConsentBytes
                    // Past this point the platform owns them, so undo is no longer honest.
                    deckState.update { it.copy(undoStack = emptyList()) }
                    _effects.send(
                        SwipeEffect.Celebrate(photoCount = mediaIds.size, freedBytes = freed)
                    )
                } else {
                    _effects.send(SwipeEffect.Notice(UiMessage.Favorited(mediaIds.size)))
                }
            } else {
                // Nothing was written, so the queue and the undo stack both stand.
                // Buy the user a little room instead of asking again immediately.
                if (kind == PendingKind.TRASH) {
                    promptPolicy.onDeclined(pendingCount = mediaIds.size)
                }
                _effects.send(SwipeEffect.Notice(UiMessage.TrashDeclined))
            }

            // The trash pass deferred the favourites so the two dialogs could not stack.
            if (kind == PendingKind.TRASH) {
                flushing = true
                try {
                    flush(PendingKind.FAVORITE, userInitiated = false)
                } finally {
                    flushing = false
                }
            }
        }
    }

    /** Clears the review history so every photo is dealt again. Photos are untouched. */
    fun onResetHistory() {
        viewModelScope.launch {
            repository.resetHistory()
            promptPolicy.onSettled()
            reload()
        }
    }

    /**
     * @param rescan whether to throw away the cached library scan. Needed when the set
     *   of photos on the device may have changed; wasteful when only the album filter
     *   did.
     */
    private fun reload(rescan: Boolean = true) {
        refillJob?.cancel()
        deckState.update {
            it.copy(
                deck = emptyList(),
                loading = true,
                exhausted = false,
                undoStack = emptyList(),
            )
        }
        refillJob = viewModelScope.launch {
            if (rescan) repository.resetQueue()
            val scope = repository.currentScope()
            deckState.update { it.copy(scope = scope) }
            fill()
        }
    }

    private fun refillIfNeeded() {
        val current = deckState.value
        if (current.exhausted || current.deck.size > REFILL_BELOW) return
        if (refillJob?.isActive == true) return
        refillJob = viewModelScope.launch { fill() }
    }

    private suspend fun fill() {
        val batch = repository.nextBatch(BATCH_SIZE)
        deckState.update { current ->
            val known = current.deck.mapTo(HashSet(current.deck.size)) { it.id }
            current.copy(
                deck = current.deck + batch.photos.filterNot { it.id in known },
                loading = false,
                exhausted = batch.photos.isEmpty(),
                undealt = batch.remainingUndealt,
            )
        }
    }

    /**
     * Settles both queues, trash first.
     *
     * Almost every photo on a device is owned by the camera app, not by us, so almost
     * every flush ends in a system dialog. Batching is what keeps that to one dialog per
     * [TrashPromptPolicy.DEFAULT_THRESHOLD] photos instead of one per swipe.
     *
     * The two queues are settled one after the other rather than together, because the
     * platform has no combined request — trashing and favouriting are separate calls
     * with separate dialogs. Favourites follow only once the trash answer is in, which
     * [onConsentResult] arranges.
     */
    private suspend fun settleQueues(userInitiated: Boolean) {
        // Two callers can reach this: the backlog watcher and the pill. Both run on the
        // main dispatcher, so setting the flag before the first suspension point is
        // enough to stop them from raising two system dialogs for the same queue.
        if (consentInFlight || flushing) return
        flushing = true

        try {
            val trashOutcome = flush(PendingKind.TRASH, userInitiated)
            // Only chain straight into favourites when the trash queue needed no dialog;
            // otherwise the favourite pass waits for the user's answer.
            if (trashOutcome != Outcome.AWAITING_CONSENT) {
                flush(PendingKind.FAVORITE, userInitiated)
            }
        } finally {
            flushing = false
        }
    }

    private enum class Outcome { SETTLED, AWAITING_CONSENT }

    private suspend fun flush(kind: PendingKind, userInitiated: Boolean): Outcome {
        when (val result = repository.flush(kind)) {
            is FlushResult.Nothing -> {
                if (kind == PendingKind.TRASH) promptPolicy.onSettled()
            }

            is FlushResult.Completed -> {
                if (kind == PendingKind.TRASH) {
                    promptPolicy.onSettled()
                    deckState.update { it.copy(undoStack = emptyList()) }
                    if (result.count > 0) {
                        _effects.send(SwipeEffect.Celebrate(result.count, result.bytes))
                    }
                }
            }

            is FlushResult.ConsentRequired -> {
                consentInFlight = true
                awaitingConsentKind = kind
                awaitingConsentIds = result.mediaIds
                // The undo stack is *not* cleared here. Nothing has been written yet,
                // and the user may still dismiss the dialog.
                deckState.update { it.copy(lastConsentBytes = result.bytes) }
                _effects.send(SwipeEffect.RequestConsent(result.intentSender))
                return Outcome.AWAITING_CONSENT
            }

            is FlushResult.Failed -> {
                // Back off so a broken volume does not retry on every swipe.
                if (kind == PendingKind.TRASH) promptPolicy.onFailed()
                if (userInitiated) _effects.send(SwipeEffect.Notice(UiMessage.TrashFailed))
            }
        }
        return Outcome.SETTLED
    }

    private data class DeckState(
        val deck: List<Photo> = emptyList(),
        val loading: Boolean = true,
        val exhausted: Boolean = false,
        /** Undecided photos still in the queue, behind everything in [deck]. */
        val undealt: Int = 0,
        val scope: AlbumScope = AlbumScope.All,
        val albums: List<Album> = emptyList(),
        /**
         * Swipes that can still be taken back, oldest first. Grows without limit until
         * a flush lands; a few thousand entries is a couple of hundred kilobytes.
         */
        val undoStack: List<Photo> = emptyList(),
        /** Byte total of the batch currently sitting in a consent dialog. */
        val lastConsentBytes: Long = 0L,
    )

    private companion object {
        /** Photos fetched per repository call. */
        const val BATCH_SIZE = 12

        /** Refill once the deck runs this low, so the next card is always warm. */
        const val REFILL_BELOW = 5

        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
