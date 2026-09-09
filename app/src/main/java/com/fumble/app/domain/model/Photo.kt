package com.fumble.app.domain.model

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * A single image from the device's [android.provider.MediaStore].
 *
 * [id] is the `MediaStore.Images.Media._ID`, which is both the stable identity used
 * for de-duplication in Room and the key used to build the content [uri].
 */
@Immutable
data class Photo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val bucketName: String?,
)
