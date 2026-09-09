package com.fumble.app.ui.swipe

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fumble.app.BuildConfig
import com.fumble.app.R
import com.fumble.app.domain.model.Photo
import com.fumble.app.domain.model.SwipeDirection
import com.fumble.app.ui.permission.PartialAccessBanner
import com.fumble.app.ui.permission.PermissionGate
import com.fumble.app.ui.permission.rememberMediaAccessState
import com.fumble.app.ui.swipe.components.AlbumSheet
import com.fumble.app.ui.swipe.components.EmptyState
import com.fumble.app.ui.swipe.components.LoadingState
import com.fumble.app.ui.swipe.components.PendingTrashPill
import com.fumble.app.ui.swipe.components.SettingsSheet
import com.fumble.app.ui.swipe.components.SwipeActionBar
import com.fumble.app.ui.swipe.components.SwipeCardStack
import com.fumble.app.ui.swipe.components.SwipeHeader
import com.fumble.app.ui.swipe.components.SwipeStackState
import com.fumble.app.ui.swipe.components.TrashCelebration
import com.fumble.app.ui.swipe.components.rememberSwipeStackState
import com.fumble.app.ui.theme.FumbleInk
import com.fumble.app.ui.theme.FumbleInkMuted
import com.fumble.app.ui.theme.FumbleSurface
import com.fumble.app.ui.theme.FumbleTrash

@Composable
fun SwipeRoute(viewModel: SwipeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val stackState = rememberSwipeStackState()

    val access = rememberMediaAccessState(onChanged = viewModel::onPhotoAccessChanged)

    var confirmingReset by remember { mutableStateOf(false) }
    var pickingAlbum by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }
    var celebration by remember { mutableStateOf<SwipeEffect.Celebrate?>(null) }

    val trashConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(approved = result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(access.access) {
        if (access.access.isUsable) viewModel.onAccessAvailable()
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SwipeEffect.RequestConsent -> trashConsentLauncher.launch(
                    IntentSenderRequest.Builder(effect.intentSender).build()
                )

                is SwipeEffect.Celebrate -> celebration = effect

                is SwipeEffect.Notice -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(context.render(effect.message))
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = FumbleInk,
                    contentColor = FumbleSurface,
                    shape = MaterialTheme.shapes.large,
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!access.access.isUsable) {
                PermissionGate(state = access)
            } else {
                SwipeContent(
                    state = state,
                    stackState = stackState,
                    showPartialAccess = access.canWidenSelection,
                    onSelectMore = access::request,
                    onSwipe = viewModel::onSwipe,
                    onUndo = viewModel::onUndo,
                    onEmptyTrash = viewModel::onEmptyTrashRequested,
                    onStartOver = { confirmingReset = true },
                    onPickAlbum = {
                        viewModel.onAlbumPickerOpened()
                        pickingAlbum = true
                    },
                    onShowAllPhotos = { viewModel.onAlbumSelected(null) },
                    onOpenSettings = { showingSettings = true },
                )
            }
        }
    }

    // Above everything, including the sheets: the moment photos actually leave.
    celebration?.let { event ->
        TrashCelebration(
            photoCount = event.photoCount,
            freedBytes = event.freedBytes,
            onDone = { celebration = null },
        )
    }

    if (showingSettings) {
        SettingsSheet(
            trashThreshold = state.trashThreshold,
            paletteId = state.paletteId,
            versionName = BuildConfig.VERSION_NAME,
            onThresholdChange = viewModel::onTrashThresholdChange,
            onPaletteChange = viewModel::onPaletteChange,
            onDismiss = { showingSettings = false },
        )
    }

    if (pickingAlbum) {
        AlbumSheet(
            albums = state.albums,
            scope = state.scope,
            onSelect = { albumId ->
                pickingAlbum = false
                viewModel.onAlbumSelected(albumId)
            },
            onDismiss = { pickingAlbum = false },
        )
    }

    if (confirmingReset) {
        ResetHistoryDialog(
            onConfirm = {
                confirmingReset = false
                viewModel.onResetHistory()
            },
            onDismiss = { confirmingReset = false },
        )
    }
}

@Composable
private fun SwipeContent(
    state: SwipeUiState,
    stackState: SwipeStackState,
    showPartialAccess: Boolean,
    onSelectMore: () -> Unit,
    onSwipe: (Photo, SwipeDirection) -> Unit,
    onUndo: () -> Unit,
    onEmptyTrash: () -> Unit,
    onStartOver: () -> Unit,
    onPickAlbum: () -> Unit,
    onShowAllPhotos: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SwipeHeader(
            remaining = state.remaining,
            freedBytes = state.freedBytes,
            scope = state.scope,
            onPickAlbum = onPickAlbum,
            onOpenSettings = onOpenSettings,
        )

        if (showPartialAccess) {
            PartialAccessBanner(
                onSelectMore = onSelectMore,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        PendingTrashPill(
            count = state.pendingTrashCount,
            bytes = state.pendingTrashBytes,
            favoriteCount = state.pendingFavoriteCount,
            onEmptyNow = onEmptyTrash,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Bottom room for the cards peeking out from under the top one.
                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 42.dp),
        ) {
            when {
                state.deck.isEmpty() && state.loading -> LoadingState()
                state.isEmpty -> EmptyState(
                    scope = state.scope,
                    onStartOver = onStartOver,
                    onShowAllPhotos = onShowAllPhotos,
                )
                else -> SwipeCardStack(
                    photos = state.deck,
                    state = stackState,
                    onDecision = onSwipe,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        SwipeActionBar(
            onSwipe = stackState::swipe,
            onUndo = onUndo,
            canUndo = state.canUndo && !stackState.isSettling,
            undoDepth = state.undoDepth,
            enabled = state.deck.isNotEmpty() && !stackState.isSettling,
        )
    }
}

@Composable
private fun ResetHistoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FumbleSurface,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text(
                text = stringResource(R.string.reset_confirm_title),
                style = MaterialTheme.typography.titleLarge,
                color = FumbleInk,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.reset_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = FumbleInkMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.reset_confirm_yes), color = FumbleTrash)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel), color = FumbleInkMuted)
            }
        },
    )
}

private fun Context.render(message: UiMessage): String = when (message) {
    UiMessage.TrashDeclined -> getString(R.string.msg_trash_declined)
    UiMessage.TrashFailed -> getString(R.string.msg_trash_failed)
    is UiMessage.Favorited -> getString(R.string.msg_favorited, message.count)
}
