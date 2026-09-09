package com.fumble.app.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The handful of choices the user has made about how the app behaves and looks.
 *
 * Deliberately not in Room: these are three scalars, unrelated to the decision
 * history, and they must not be caught up in a destructive migration of that table.
 *
 * The two settings that the UI reacts to are exposed as flows so a change in the
 * settings sheet reaches the screen without anything having to poll. The album is a
 * plain property because only the repository reads it, always under its own lock.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** MediaStore `BUCKET_ID`, or `null` for the whole library. */
    var selectedAlbumId: Long?
        get() = if (prefs.contains(KEY_ALBUM)) prefs.getLong(KEY_ALBUM, 0L) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_ALBUM) else putLong(KEY_ALBUM, value)
        }

    private val _trashThreshold =
        MutableStateFlow(prefs.getInt(KEY_THRESHOLD, DEFAULT_TRASH_THRESHOLD))

    /** Queued left swipes before the app asks to empty them. */
    val trashThreshold: StateFlow<Int> = _trashThreshold.asStateFlow()

    fun setTrashThreshold(value: Int) {
        val clamped = value.coerceIn(MIN_TRASH_THRESHOLD, MAX_TRASH_THRESHOLD)
        _trashThreshold.value = clamped
        prefs.edit { putInt(KEY_THRESHOLD, clamped) }
    }

    private val _paletteId =
        MutableStateFlow(prefs.getString(KEY_PALETTE, null) ?: DEFAULT_PALETTE_ID)

    val paletteId: StateFlow<String> = _paletteId.asStateFlow()

    fun setPaletteId(value: String) {
        _paletteId.value = value
        prefs.edit { putString(KEY_PALETTE, value) }
    }

    companion object {
        const val DEFAULT_TRASH_THRESHOLD = 100
        const val MIN_TRASH_THRESHOLD = 5
        const val MAX_TRASH_THRESHOLD = 500
        const val DEFAULT_PALETTE_ID = "daylight"

        /**
         * Pre-rename name, kept deliberately. Renaming the file would silently reset
         * everyone's album, threshold and palette on upgrade.
         */
        private const val FILE_NAME = "inder_scope"
        private const val KEY_ALBUM = "selected_album_id"
        private const val KEY_THRESHOLD = "trash_threshold"
        private const val KEY_PALETTE = "palette_id"
    }
}
