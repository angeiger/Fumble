package com.fumble.app.domain.model

/**
 * Running totals for the current device, derived entirely from the decision history.
 *
 * [freedBytes] only counts photos whose `IS_TRASHED` write actually landed, so the
 * number shown to the user never overstates what was reclaimed.
 */
data class LibraryStats(
    val kept: Int = 0,
    val trashed: Int = 0,
    val favorited: Int = 0,
    val freedBytes: Long = 0L,
)

/** A swiped photo whose MediaStore write is still queued behind a consent dialog. */
data class PendingWrite(
    val mediaId: Long,
    val sizeBytes: Long,
)
