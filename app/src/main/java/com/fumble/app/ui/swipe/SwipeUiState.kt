package com.fumble.app.ui.swipe

import android.content.IntentSender
import androidx.compose.runtime.Immutable
import com.fumble.app.domain.model.Album
import com.fumble.app.domain.model.AlbumScope
import com.fumble.app.domain.model.Photo

@Immutable
data class SwipeUiState(
    /** True until the first batch has been fetched. */
    val loading: Boolean = true,

    /** Cards to draw, top card first. */
    val deck: List<Photo> = emptyList(),

    /** Photos still awaiting a decision in the current [scope], including [deck]. */
    val remaining: Int = 0,

    /** Which album the deck is dealing from. */
    val scope: AlbumScope = AlbumScope.All,

    /** Albums offered by the picker. Loaded on demand, so empty until it is opened. */
    val albums: List<Album> = emptyList(),

    val keptCount: Int = 0,
    val trashedCount: Int = 0,
    val favoritedCount: Int = 0,
    val freedBytes: Long = 0L,

    /** Left swipes recorded but not yet written to the system trash. */
    val pendingTrashCount: Int = 0,
    val pendingTrashBytes: Long = 0L,

    /** Up swipes recorded but not yet written as favourites. */
    val pendingFavoriteCount: Int = 0,

    val canUndo: Boolean = false,

    /** How many swipes can still be taken back, i.e. how far the undo stack reaches. */
    val undoDepth: Int = 0,

    /** MediaStore has no more undecided photos to hand out. */
    val exhausted: Boolean = false,

    /** Queued left swipes before the app offers to empty them. User-set. */
    val trashThreshold: Int = 100,

    /** Id of the active palette, from settings. */
    val paletteId: String = "daylight",
) {
    val isEmpty: Boolean get() = deck.isEmpty() && exhausted && !loading

    /** Anything at all waiting on a system dialog. */
    val hasPendingWork: Boolean get() = pendingTrashCount > 0 || pendingFavoriteCount > 0
}

/** One-shot things the screen has to do that cannot be expressed as state. */
sealed interface SwipeEffect {

    /**
     * Launch this via `StartIntentSenderForResult` to get trash approval, then report
     * back with [SwipeViewModel.onTrashConsentResult].
     *
     * The affected ids are deliberately not carried here: the ViewModel holds them, so
     * they survive the activity being recreated behind the system dialog.
     */
    data class RequestConsent(val intentSender: IntentSender) : SwipeEffect

    data class Notice(val message: UiMessage) : SwipeEffect

    /**
     * Photos actually reached the trash. Worth more than a snackbar: this is the only
     * moment in the app where something is genuinely accomplished.
     */
    data class Celebrate(val photoCount: Int, val freedBytes: Long) : SwipeEffect
}

/** Screen-facing messages. Kept as data so the ViewModel never touches a Context. */
sealed interface UiMessage {
    data object TrashDeclined : UiMessage
    data object TrashFailed : UiMessage
    data class Favorited(val count: Int) : UiMessage
}
