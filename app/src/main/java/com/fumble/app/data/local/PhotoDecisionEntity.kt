package com.fumble.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fumble.app.domain.model.Decision

/**
 * One row per photo the user has already swiped. This table is the entire reason a
 * photo is never shown twice: [mediaId] is the `MediaStore._ID`, and the repository
 * filters every MediaStore page against the set of ids stored here.
 *
 * [trashApplied] tracks the second half of a left swipe. A swipe is recorded
 * immediately (so the card never comes back), but flipping `IS_TRASHED` may need a
 * system consent dialog, which is batched. Until that succeeds the row sits here as
 * pending work, and it survives process death.
 */
@Entity(
    tableName = "photo_decision",
    indices = [Index(value = ["decision", "trash_applied"])],
)
data class PhotoDecisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: Long,

    @ColumnInfo(name = "content_uri")
    val contentUri: String,

    /** Stored name of [com.fumble.app.domain.model.Decision]. */
    @ColumnInfo(name = "decision")
    val decision: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "decided_at")
    val decidedAt: Long,

    @ColumnInfo(name = "trash_applied")
    val trashApplied: Boolean,

    /**
     * Whether `IS_FAVORITE` has actually been written for a [Decision.FAVORITE] row.
     *
     * A separate column from [trashApplied] rather than one shared "settled" flag:
     * the two are different MediaStore writes needing separate consent dialogs, and
     * collapsing them would make it impossible to tell which half of a batch landed.
     */
    @ColumnInfo(name = "favorite_applied", defaultValue = "0")
    val favoriteApplied: Boolean = false,
)

/**
 * Aggregate counters projected straight out of SQLite by [PhotoDecisionDao.observeStats].
 *
 * No default parameter values here on purpose: they would give Room a second,
 * zero-argument constructor to choose between.
 */
data class StatsProjection(
    @ColumnInfo(name = "kept") val kept: Int,
    @ColumnInfo(name = "trashed") val trashed: Int,
    @ColumnInfo(name = "favorited") val favorited: Int,
    @ColumnInfo(name = "freed_bytes") val freedBytes: Long,
)
