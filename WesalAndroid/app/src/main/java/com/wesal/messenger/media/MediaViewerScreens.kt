package com.wesal.messenger.media

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.WesalDeepBlack
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.presentation.components.WesalMediaKind
import com.wesal.messenger.presentation.components.rememberSavePermissionGate
import com.wesal.messenger.presentation.components.saveMediaToGallery
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FullscreenImageViewer(
    imageModel: Any,
    onClose: () -> Unit,
    canSave: Boolean = true
) {
    val context = LocalContext.current
    val strings = LocalWesalStrings.current
    val scope = rememberCoroutineScope()
    val savePermissionGate = rememberSavePermissionGate()
    var isSaving by remember { mutableStateOf(false) }

    val resolvedModel = (imageModel as? String)?.let {
        com.wesal.messenger.presentation.components.wesalImageModel(it)
    } ?: imageModel
    // Round-4 fix: chat photos reach this viewer as a decrypted java.io.File —
    // the old when() only accepted String/Uri, so the save button NEVER rendered
    // for chat media. File/Uri/String are all valid sources for the shared saver.
    val saveSource: Any? = when (imageModel) {
        is File -> imageModel
        is String -> imageModel.takeIf { it.isNotBlank() }
        is Uri -> imageModel
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WesalDeepBlack)
    ) {
        AsyncImage(
            model = resolvedModel,
            contentDescription = "عرض الصورة",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canSave && saveSource != null) {
                Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape) {
                    IconButton(
                        enabled = !isSaving,
                        onClick = {
                            savePermissionGate {
                                isSaving = true
                                scope.launch {
                                    saveMediaToGallery(context, saveSource, WesalMediaKind.IMAGE, fileNamePrefix = "WESAL_IMG")
                                        .onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (strings.isEnglish) "Saved to gallery ✓" else "تم حفظ الصورة في المعرض ✓",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        .onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                err.localizedMessage ?: if (strings.isEnglish) "Save failed" else "تعذر حفظ الصورة",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    isSaving = false
                                }
                            }
                        }
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = if (strings.isEnglish) "Save" else "حفظ الصورة", tint = Color.White)
                        }
                    }
                }
            }
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun VideoPlayerBox(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(uri) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier
    )
}
