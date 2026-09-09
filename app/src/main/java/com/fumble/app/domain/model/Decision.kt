package com.fumble.app.domain.model

/** What the user decided about a photo. Persisted by name, so do not rename members. */
enum class Decision {
    /** Swiped right. The photo stays exactly where it is; we only remember we asked. */
    KEEP,

    /** Swiped left. Queued for `MediaStore.MediaColumns.IS_TRASHED = 1`. */
    TRASH,

    /**
     * Swiped up. A keep that also gets `MediaStore.MediaColumns.IS_FAVORITE = 1`, so
     * the photo resurfaces in the gallery's own Favourites album instead of being
     * forgotten again the moment the card leaves the screen.
     */
    FAVORITE,
}

/**
 * Direction of a card gesture.
 *
 * [signX] and [signY] are the multipliers used to fling the card off screen, which is
 * why up is a first-class direction rather than a flag on a horizontal swipe.
 */
enum class SwipeDirection(val signX: Float, val signY: Float) {
    LEFT(-1f, 0f),
    RIGHT(1f, 0f),
    UP(0f, -1f),
    ;

    val decision: Decision
        get() = when (this) {
            RIGHT -> Decision.KEEP
            LEFT -> Decision.TRASH
            UP -> Decision.FAVORITE
        }
}
