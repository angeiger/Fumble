package com.fumble.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDecisionDao {

    /**
     * Every id the user has already ruled on. Read once per session and kept in a
     * memory set by the repository; a `Long` per reviewed photo is cheap even for
     * very large libraries.
     */
    @Query("SELECT media_id FROM photo_decision")
    suspend fun allDecidedIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(decision: PhotoDecisionEntity)

    /** Used by undo: forgetting a photo puts it back into the pool. */
    @Query("DELETE FROM photo_decision WHERE media_id = :mediaId")
    suspend fun forget(mediaId: Long)

    // --- Trash queue -------------------------------------------------------

    @Query(
        """
        SELECT * FROM photo_decision
        WHERE decision = 'TRASH' AND trash_applied = 0
        ORDER BY decided_at ASC
        """
    )
    suspend fun pendingTrash(): List<PhotoDecisionEntity>

    @Query(
        """
        SELECT * FROM photo_decision
        WHERE decision = 'TRASH' AND trash_applied = 0
        ORDER BY decided_at ASC
        """
    )
    fun observePendingTrash(): Flow<List<PhotoDecisionEntity>>

    @Query("UPDATE photo_decision SET trash_applied = 1 WHERE media_id IN (:mediaIds)")
    suspend fun markTrashApplied(mediaIds: List<Long>)

    // --- Favourite queue ---------------------------------------------------

    @Query(
        """
        SELECT * FROM photo_decision
        WHERE decision = 'FAVORITE' AND favorite_applied = 0
        ORDER BY decided_at ASC
        """
    )
    suspend fun pendingFavorites(): List<PhotoDecisionEntity>

    @Query(
        """
        SELECT * FROM photo_decision
        WHERE decision = 'FAVORITE' AND favorite_applied = 0
        ORDER BY decided_at ASC
        """
    )
    fun observePendingFavorites(): Flow<List<PhotoDecisionEntity>>

    @Query("UPDATE photo_decision SET favorite_applied = 1 WHERE media_id IN (:mediaIds)")
    suspend fun markFavoriteApplied(mediaIds: List<Long>)

    // --- Stats -------------------------------------------------------------

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN decision = 'KEEP' THEN 1 ELSE 0 END), 0) AS kept,
            COALESCE(SUM(CASE WHEN decision = 'TRASH' THEN 1 ELSE 0 END), 0) AS trashed,
            COALESCE(SUM(CASE WHEN decision = 'FAVORITE' THEN 1 ELSE 0 END), 0) AS favorited,
            COALESCE(SUM(CASE WHEN decision = 'TRASH' AND trash_applied = 1
                              THEN size_bytes ELSE 0 END), 0) AS freed_bytes
        FROM photo_decision
        """
    )
    fun observeStats(): Flow<StatsProjection>

    @Query("DELETE FROM photo_decision")
    suspend fun clear()
}
