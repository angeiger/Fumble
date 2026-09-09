package com.fumble.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * A folder on the device, as MediaStore sees it — `Camera`, `Screenshots`, `Download`
 * and so on.
 *
 * [photoCount] is the number of photos in it the user has **not** ruled on yet, so the
 * list doubles as a progress readout and shrinks as albums get worked through.
 */
@Immutable
data class Album(
    val id: Long,
    val name: String,
    val photoCount: Int,
)

/** Which photos the deck is currently dealing from. */
@Immutable
sealed interface AlbumScope {

    /** Everything on the device. */
    data object All : AlbumScope

    data class Only(val id: Long, val name: String) : AlbumScope

    val albumId: Long?
        get() = (this as? Only)?.id
}
