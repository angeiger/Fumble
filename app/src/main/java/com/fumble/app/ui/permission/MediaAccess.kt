package com.fumble.app.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** How much of the gallery this app is currently allowed to read. */
enum class MediaAccess {
    /** No photo access at all. */
    DENIED,

    /**
     * Android 14+ "Select photos". Only the images the user hand-picked are visible,
     * and they can widen the selection at any time.
     */
    PARTIAL,

    /** The whole image library. */
    FULL,
    ;

    val isUsable: Boolean get() = this != DENIED
}

/**
 * The permissions to ask for, which differ by release:
 * - Android 14+: the granular image permission plus its partial-access companion, so
 *   the system offers "Select photos" alongside "Allow all".
 * - Android 13: the granular image permission.
 * - Android 11-12L: the legacy read permission.
 */
val MediaPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
    )

    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Stable
class MediaAccessState internal constructor(private val context: Context) {

    internal var launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>? =
        null

    var access: MediaAccess by mutableStateOf(context.currentMediaAccess())
        internal set

    /** The user said no and turned off the system prompt; only Settings will help now. */
    var permanentlyDenied: Boolean by mutableStateOf(false)
        internal set

    /** On Android 14+ this re-opens the photo picker so more photos can be selected. */
    val canWidenSelection: Boolean
        get() = access == MediaAccess.PARTIAL

    fun request() {
        launcher?.launch(MediaPermissions)
    }

    fun refresh() {
        access = context.currentMediaAccess()
        if (access.isUsable) permanentlyDenied = false
    }

    fun openSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Tracks photo access and keeps it fresh.
 *
 * The state is re-read on every resume, so returning from system Settings or from the
 * "Select photos" sheet is reflected immediately.
 *
 * @param onChanged fired when an *existing* grant is re-scoped, i.e. the user changed
 *   which photos this app can see. A first grant is not reported here — callers should
 *   react to [MediaAccessState.access] becoming usable instead.
 */
@Composable
fun rememberMediaAccessState(onChanged: () -> Unit = {}): MediaAccessState {
    val context = LocalContext.current
    val latestOnChanged by rememberUpdatedState(onChanged)
    val state = remember(context) { MediaAccessState(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val previous = state.access
        state.refresh()

        if (!state.access.isUsable) {
            // No rationale offered after a denial means the system will not ask again.
            val activity = context.findActivity()
            state.permanentlyDenied = activity != null && MediaPermissions.none {
                activity.shouldShowRequestPermissionRationale(it)
            }
        } else if (previous.isUsable) {
            latestOnChanged()
        }
    }

    // Plain field, not snapshot state: assigning the same launcher on every
    // recomposition is idempotent and must not itself trigger one.
    state.launcher = launcher

    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }

    return state
}

private fun Context.currentMediaAccess(): MediaAccess = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        when {
            isGranted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.FULL

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
                MediaAccess.PARTIAL

            else -> MediaAccess.DENIED
        }

    isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL

    else -> MediaAccess.DENIED
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
