package com.wesal.messenger.crop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CropActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CROP_MODE = "wesal_crop_mode"
        const val EXTRA_TITLE = "wesal_crop_title"
        const val EXTRA_BG_REMOVED = "wesal_crop_bg_removed"
        const val MODE_CIRCLE_AVATAR = "CIRCLE_AVATAR"
        const val MODE_COVER_16_9 = "COVER_16_9"
        const val MODE_SQUARE = "SQUARE"
        const val MODE_WALLPAPER = "WALLPAPER"

        fun createIntent(
            context: Context,
            sourceUri: Uri,
            mode: String = MODE_CIRCLE_AVATAR,
            title: String? = null
        ): Intent = Intent(context, CropActivity::class.java).apply {
            data = sourceUri
            putExtra(EXTRA_CROP_MODE, mode)
            if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data
        val mode = intent.getStringExtra(EXTRA_CROP_MODE) ?: MODE_CIRCLE_AVATAR
        val customTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val bmp = uri?.let { loadBitmap(it) }
        if (bmp == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        setContent {
            CropScreen(
                initialBitmap = bmp,
                mode = mode,
                customTitle = customTitle,
                onCancel = {
                    setResult(RESULT_CANCELED)
                    finish()
                },
                onDone = { out, hasAlpha ->
                    val ext = if (hasAlpha) "png" else "jpg"
                    val f = File(cacheDir, "crop_${System.currentTimeMillis()}.$ext")
                    f.outputStream().use { stream ->
                        if (hasAlpha) {
                            out.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        } else {
                            out.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                        }
                    }
                    val resultIntent = Intent()
                        .setData(Uri.fromFile(f))
                        .putExtra(EXTRA_BG_REMOVED, hasAlpha)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            )
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val rot = contentResolver.openInputStream(uri)?.use {
            when (
                android.media.ExifInterface(it)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            ) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (b != null && rot != 0f) {
            val m = Matrix()
            m.postRotate(rot)
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        b
    } catch (_: Exception) {
        null
    }
}

private enum class CropToolMode {
    MOVE,
    SMART_OBJECT,
    LASSO_ERASE,
    ERASE,
    RESTORE
}

@Composable
private fun CropScreen(
    initialBitmap: Bitmap,
    mode: String,
    customTitle: String,
    onCancel: () -> Unit,
    onDone: (Bitmap, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val argbInitial = remember(initialBitmap) {
        if (initialBitmap.config == Bitmap.Config.ARGB_8888) initialBitmap
        else initialBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
    var workingBitmap by remember { mutableStateOf(argbInitial.copy(Bitmap.Config.ARGB_8888, true)) }
    var bitmapVersion by remember { mutableIntStateOf(0) }
    var bgRemoved by remember { mutableStateOf(false) }
    var glowPreview by remember { mutableStateOf(true) }
    var isProcessingBg by remember { mutableStateOf(false) }
    var bgError by remember { mutableStateOf<String?>(null) }

    var toolMode by remember { mutableStateOf(CropToolMode.MOVE) }
    var brushSizePx by remember { mutableFloatStateOf(46f) }
    var smartTolerance by remember { mutableFloatStateOf(78f) }
    var touchCursor by remember { mutableStateOf<Offset?>(null) }
    val undoStack = remember { mutableStateListOf<Bitmap>() }

    // Object selection states (used by SMART_OBJECT & LASSO_ERASE)
    var lassoBitmapPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var pendingSelectionMask by remember { mutableStateOf<BooleanArray?>(null) }
    var pendingOverlayBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val img = remember(workingBitmap, bitmapVersion) { workingBitmap.asImageBitmap() }
    val overlayImg = remember(pendingOverlayBitmap) { pendingOverlayBitmap?.asImageBitmap() }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val bw = workingBitmap.width.toFloat()
    val bh = workingBitmap.height.toFloat()

    val aspectRatio = when (mode) {
        CropActivity.MODE_COVER_16_9 -> 16f / 9f
        CropActivity.MODE_WALLPAPER -> 9f / 16f
        else -> 1f
    }

    fun baseScale(w: Float, h: Float): Float {
        if (w <= 0f || h <= 0f) return 1f
        return maxOf(w / bw, h / bh)
    }

    fun clampOffset(o: Offset, z: Float, w: Float, h: Float): Offset {
        val total = baseScale(w, h) * z
        val mx = maxOf(0f, (bw * total - w) / 2f)
        val my = maxOf(0f, (bh * total - h) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    fun screenToBitmap(screenPos: Offset, vw: Float, vh: Float): Offset {
        val total = baseScale(vw, vh) * zoomLevel
        return Offset(
            x = ((screenPos.x - (vw / 2f + offset.x)) / total + bw / 2f).coerceIn(0f, bw - 1f),
            y = ((screenPos.y - (vh / 2f + offset.y)) / total + bh / 2f).coerceIn(0f, bh - 1f)
        )
    }

    fun pushUndoSnapshot() {
        if (undoStack.size >= 15) undoStack.removeAt(0)
        undoStack.add(workingBitmap.copy(Bitmap.Config.ARGB_8888, true))
    }

    fun clearPendingSelection() {
        lassoBitmapPoints = emptyList()
        pendingSelectionMask = null
        pendingOverlayBitmap = null
    }

    fun erasePendingSelection() {
        val mask = pendingSelectionMask ?: return
        val w = workingBitmap.width
        val h = workingBitmap.height
        if (mask.size != w * h) {
            clearPendingSelection()
            return
        }
        pushUndoSnapshot()
        val px = IntArray(w * h)
        workingBitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            if (mask[i]) {
                px[i] = px[i] and 0x00FFFFFF
            }
        }
        // Soften 1px boundary around newly erased area so edges stay smooth
        val smoothed = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (!mask[idx] && (px[idx] ushr 24) > 80) {
                    val adjErased = mask[idx - 1] || mask[idx + 1] || mask[idx - w] || mask[idx + w]
                    if (adjErased) {
                        val a = ((px[idx] ushr 24) and 0xFF) / 2
                        smoothed[idx] = (a shl 24) or (px[idx] and 0x00FFFFFF)
                    }
                }
            }
        }
        workingBitmap.setPixels(smoothed, 0, w, 0, 0, w, h)
        clearPendingSelection()
        bgRemoved = true
        bgError = null
        bitmapVersion++
    }

    fun applyBrushAt(screenPos: Offset, eraseMode: Boolean) {
        val vw = viewportSize.width.toFloat()
        val vh = viewportSize.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val total = baseScale(vw, vh) * zoomLevel
        val bx = ((screenPos.x - (vw / 2f + offset.x)) / total + bw / 2f).roundToInt()
        val by = ((screenPos.y - (vh / 2f + offset.y)) / total + bh / 2f).roundToInt()
        val radiusPx = (brushSizePx / total).coerceAtLeast(3f)
        val rInt = radiusPx.toInt() + 1
        val w = workingBitmap.width
        val h = workingBitmap.height
        val x0 = (bx - rInt).coerceIn(0, w - 1)
        val x1 = (bx + rInt).coerceIn(0, w - 1)
        val y0 = (by - rInt).coerceIn(0, h - 1)
        val y1 = (by + rInt).coerceIn(0, h - 1)
        val pw = x1 - x0 + 1
        val ph = y1 - y0 + 1
        if (pw <= 0 || ph <= 0) return

        val workSlice = IntArray(pw * ph)
        workingBitmap.getPixels(workSlice, 0, pw, x0, y0, pw, ph)
        val origSlice = if (!eraseMode) {
            IntArray(pw * ph).also { argbInitial.getPixels(it, 0, pw, x0, y0, pw, ph) }
        } else null

        for (dy in 0 until ph) {
            val py = y0 + dy
            for (dx in 0 until pw) {
                val px = x0 + dx
                val dist = hypot((px - bx).toFloat(), (py - by).toFloat())
                if (dist <= radiusPx) {
                    val idx = dy * pw + dx
                    if (eraseMode) {
                        val factor = ((dist / radiusPx - 0.72f) / 0.28f).coerceIn(0f, 1f)
                        val oldA = (workSlice[idx] ushr 24) and 0xFF
                        val newA = (oldA * factor).roundToInt().coerceIn(0, oldA)
                        workSlice[idx] = (newA shl 24) or (workSlice[idx] and 0x00FFFFFF)
                    } else if (origSlice != null) {
                        workSlice[idx] = origSlice[idx]
                    }
                }
            }
        }
        workingBitmap.setPixels(workSlice, 0, pw, x0, y0, pw, ph)
        if (eraseMode) bgRemoved = true
        bitmapVersion++
    }

    val headerTitle = when {
        customTitle.isNotBlank() -> customTitle
        mode == CropActivity.MODE_COVER_16_9 -> "تحديد جزء الغلاف (16:9)"
        mode == CropActivity.MODE_WALLPAPER -> "تحديد جزء الخلفية"
        mode == CropActivity.MODE_SQUARE -> "قص الصورة"
        else -> "تحديد الجزء الظاهر من الصورة"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05090B))
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = headerTitle,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp)
        )
        Text(
            text = when (toolMode) {
                CropToolMode.MOVE -> "اسحب وحرّك الصورة وكبّرها بإصبعين لاختيار الجزء المطلوب"
                CropToolMode.SMART_OBJECT -> "اضغط على أي عنصر زائد أو جزء غير مرغوب لتحديده ومسحه بسهولة"
                CropToolMode.LASSO_ERASE -> "ارسم حلقة حول العنصر الزائد غير المرغوب فيه لتحديده ثم مسحه"
                CropToolMode.ERASE -> "مرّر إصبعك لمسح الأجزاء الزائدة يدوياً بدقة"
                CropToolMode.RESTORE -> "مرّر إصبعك لاسترجاع الأجزاء الممسوحة"
            },
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp, start = 16.dp, end = 16.dp)
        )

        Spacer(Modifier.weight(1f))

        val cropBoxModifier = when (mode) {
            CropActivity.MODE_COVER_16_9 -> Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .aspectRatio(aspectRatio)
            CropActivity.MODE_WALLPAPER -> Modifier
                .fillMaxHeight(0.50f)
                .aspectRatio(aspectRatio)
            else -> Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .aspectRatio(1f)
        }

        Box(
            modifier = cropBoxModifier
                .background(if (bgRemoved) Color(0xFF0B1518) else Color.Black)
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
                .pointerInput(viewportSize, toolMode, zoomLevel, offset, brushSizePx, smartTolerance, bitmapVersion) {
                    when (toolMode) {
                        CropToolMode.MOVE -> {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val vw = viewportSize.width.toFloat()
                                val vh = viewportSize.height.toFloat()
                                val nz = (zoomLevel * zoom).coerceIn(1f, 6f)
                                zoomLevel = nz
                                offset = clampOffset(offset + pan, nz, vw, vh)
                            }
                        }
                        CropToolMode.SMART_OBJECT -> {
                            detectTapGestures { tapPos ->
                                val vw = viewportSize.width.toFloat()
                                val vh = viewportSize.height.toFloat()
                                if (vw <= 0f || vh <= 0f) return@detectTapGestures
                                val bp = screenToBitmap(tapPos, vw, vh)
                                bgError = null
                                scope.launch {
                                    val result = selectUnwantedElementAt(
                                        source = workingBitmap,
                                        tapX = bp.x.roundToInt(),
                                        tapY = bp.y.roundToInt(),
                                        colorTolerance = smartTolerance.roundToInt()
                                    )
                                    if (result == null) {
                                        clearPendingSelection()
                                        bgError = "اضغط مباشرة فوق العنصر الزائد المراد تحديده"
                                    } else {
                                        pendingSelectionMask = result.first
                                        pendingOverlayBitmap = result.second
                                    }
                                }
                            }
                        }
                        CropToolMode.LASSO_ERASE -> {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val vw = viewportSize.width.toFloat()
                                    val vh = viewportSize.height.toFloat()
                                    if (vw <= 0f || vh <= 0f) return@detectDragGestures
                                    clearPendingSelection()
                                    bgError = null
                                    lassoBitmapPoints = listOf(screenToBitmap(start, vw, vh))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val vw = viewportSize.width.toFloat()
                                    val vh = viewportSize.height.toFloat()
                                    if (vw <= 0f || vh <= 0f) return@detectDragGestures
                                    val bp = screenToBitmap(change.position, vw, vh)
                                    val last = lassoBitmapPoints.lastOrNull()
                                    if (last == null || hypot(bp.x - last.x, bp.y - last.y) >= 3f) {
                                        lassoBitmapPoints = lassoBitmapPoints + bp
                                    }
                                },
                                onDragEnd = {
                                    val poly = lassoBitmapPoints
                                    if (poly.size < 3) {
                                        lassoBitmapPoints = emptyList()
                                        return@detectDragGestures
                                    }
                                    scope.launch {
                                        val result = buildLassoSelectionMask(workingBitmap, poly)
                                        if (result == null) {
                                            clearPendingSelection()
                                            bgError = "لم يتم العثور على عنصر ظاهر داخل التحديد"
                                        } else {
                                            pendingSelectionMask = result.first
                                            pendingOverlayBitmap = result.second
                                        }
                                    }
                                },
                                onDragCancel = { lassoBitmapPoints = emptyList() }
                            )
                        }
                        CropToolMode.ERASE, CropToolMode.RESTORE -> {
                            detectDragGestures(
                                onDragStart = { start ->
                                    clearPendingSelection()
                                    pushUndoSnapshot()
                                    touchCursor = start
                                    applyBrushAt(start, toolMode == CropToolMode.ERASE)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    touchCursor = change.position
                                    applyBrushAt(change.position, toolMode == CropToolMode.ERASE)
                                },
                                onDragEnd = { touchCursor = null },
                                onDragCancel = { touchCursor = null }
                            )
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val vw = size.width
                val vh = size.height
                if (bgRemoved) {
                    if (glowPreview) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF004D40), Color(0xFF061E24), Color(0xFF04090C)),
                                center = Offset(vw / 2f, vh / 2f),
                                radius = maxOf(vw, vh) * 0.72f
                            )
                        )
                    } else {
                        val tile = 24.dp.toPx()
                        val cols = (vw / tile).toInt() + 1
                        val rows = (vh / tile).toInt() + 1
                        for (r in 0..rows) {
                            for (c in 0..cols) {
                                val checkColor = if ((r + c) % 2 == 0) Color(0xFF1D272D) else Color(0xFF10161A)
                                drawRect(
                                    color = checkColor,
                                    topLeft = Offset(c * tile, r * tile),
                                    size = Size(tile, tile)
                                )
                            }
                        }
                    }
                }

                val total = baseScale(vw, vh) * zoomLevel
                withTransform({
                    translate(vw / 2f + offset.x, vh / 2f + offset.y)
                    scale(total, total, pivot = Offset.Zero)
                    translate(-bw / 2f, -bh / 2f)
                }) {
                    drawImage(img)
                    overlayImg?.let { maskImg ->
                        drawImage(maskImg)
                    }
                    if (lassoBitmapPoints.size >= 2) {
                        val path = Path().apply {
                            moveTo(lassoBitmapPoints[0].x, lassoBitmapPoints[0].y)
                            for (i in 1 until lassoBitmapPoints.size) {
                                lineTo(lassoBitmapPoints[i].x, lassoBitmapPoints[i].y)
                            }
                            if (pendingSelectionMask != null) close()
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFFFF1744),
                            style = Stroke(
                                width = (4f / total).coerceAtLeast(1.5f),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(
                                        (12f / total).coerceAtLeast(4f),
                                        (8f / total).coerceAtLeast(3f)
                                    )
                                )
                            )
                        )
                    }
                }

                touchCursor?.let { cur ->
                    drawCircle(
                        color = if (toolMode == CropToolMode.ERASE) Color(0xFFFF5252) else Color(0xFF00E676),
                        radius = brushSizePx,
                        center = cur,
                        style = Stroke(width = 3f)
                    )
                }

                if (mode == CropActivity.MODE_CIRCLE_AVATAR) {
                    drawCircle(
                        color = Color(0xFF00D5E8),
                        radius = size.minDimension / 2f - 2f,
                        style = Stroke(width = 4f)
                    )
                } else {
                    drawRect(
                        color = Color(0xFF00D5E8),
                        style = Stroke(width = 4f)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Selection confirmation action bar when an unwanted object is highlighted
        if (pendingSelectionMask != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { erasePendingSelection() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF1744),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("مسح العنصر المحدد", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { clearPendingSelection() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("إلغاء التحديد")
                }
            }
        }

        bgError?.let { err ->
            Text(
                text = err,
                color = Color(0xFFFF6E87),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // Tool mode selector (Move, Smart Object Tap, Lasso Object Select, Manual Brush Erase, Restore) + Undo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = toolMode == CropToolMode.MOVE,
                onClick = { toolMode = CropToolMode.MOVE },
                leadingIcon = { Icon(Icons.Default.OpenWith, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("تحريك", maxLines = 1) }
            )
            FilterChip(
                selected = toolMode == CropToolMode.SMART_OBJECT,
                onClick = {
                    clearPendingSelection()
                    toolMode = CropToolMode.SMART_OBJECT
                },
                leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("تحديد ذكي", maxLines = 1) }
            )
            FilterChip(
                selected = toolMode == CropToolMode.LASSO_ERASE,
                onClick = {
                    clearPendingSelection()
                    toolMode = CropToolMode.LASSO_ERASE
                },
                leadingIcon = { Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("تطويق عنصر", maxLines = 1) }
            )
            FilterChip(
                selected = toolMode == CropToolMode.ERASE,
                onClick = {
                    clearPendingSelection()
                    toolMode = CropToolMode.ERASE
                },
                leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("ممحاة", maxLines = 1) }
            )
            FilterChip(
                selected = toolMode == CropToolMode.RESTORE,
                onClick = {
                    clearPendingSelection()
                    toolMode = CropToolMode.RESTORE
                },
                leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("استعادة", maxLines = 1) }
            )
            if (undoStack.isNotEmpty()) {
                IconButton(
                    onClick = {
                        clearPendingSelection()
                        val last = undoStack.removeAt(undoStack.lastIndex)
                        workingBitmap = last
                        bitmapVersion++
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "تراجع", tint = Color(0xFF00D5E8))
                }
            }
        }

        if (toolMode == CropToolMode.ERASE || toolMode == CropToolMode.RESTORE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("حجم الفرشاة", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = brushSizePx,
                    onValueChange = { brushSizePx = it },
                    valueRange = 16f..96f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00D5E8),
                        activeTrackColor = Color(0xFF00D66B)
                    )
                )
            }
        } else if (toolMode == CropToolMode.SMART_OBJECT) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("حساسية التحديد", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = smartTolerance,
                    onValueChange = { smartTolerance = it },
                    valueRange = 30f..145f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00D5E8),
                        activeTrackColor = Color(0xFFFF1744)
                    )
                )
            }
        }

        // Auto Background removal & Glow Preview / Reset tools
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    clearPendingSelection()
                    if (bgRemoved) {
                        pushUndoSnapshot()
                        workingBitmap = argbInitial.copy(Bitmap.Config.ARGB_8888, true)
                        bitmapVersion++
                        bgRemoved = false
                        bgError = null
                    } else {
                        isProcessingBg = true
                        bgError = null
                        scope.launch {
                            val segmented = removeBackgroundPreservingPerson(argbInitial)
                            isProcessingBg = false
                            if (segmented != null) {
                                pushUndoSnapshot()
                                workingBitmap = segmented.copy(Bitmap.Config.ARGB_8888, true)
                                bitmapVersion++
                                bgRemoved = true
                                // Immediately switch to Smart Object removal so leftover elements can be tapped & erased easily
                                toolMode = CropToolMode.SMART_OBJECT
                            } else {
                                bgError = "تعذر عزل الشخص تلقائياً؛ استخدم أداة التحديد أو الممحاة اليدوية"
                            }
                        }
                    }
                },
                enabled = !isProcessingBg,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00D5E8))
            ) {
                if (isProcessingBg) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF00D5E8), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("جاري العزل...", color = Color.White, maxLines = 1)
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (bgRemoved) "استرجاع الكل" else "عزل تلقائي", color = Color.White, maxLines = 1)
                }
            }

            if (bgRemoved) {
                OutlinedButton(
                    onClick = { glowPreview = !glowPreview },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E676))
                ) {
                    Text(if (glowPreview) "توهج ✓" else "شفاف", color = Color.White, maxLines = 1)
                }
            }

            OutlinedButton(
                onClick = {
                    zoomLevel = 1f
                    offset = Offset.Zero
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("ضبط", maxLines = 1)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("إلغاء", color = Color.White)
            }
            Button(
                onClick = {
                    clearPendingSelection()
                    val vw = viewportSize.width.toFloat().coerceAtLeast(1f)
                    val vh = viewportSize.height.toFloat().coerceAtLeast(1f)
                    val total = baseScale(vw, vh) * zoomLevel
                    val srcW = (vw / total).coerceIn(1f, bw)
                    val srcH = (vh / total).coerceIn(1f, bh)
                    val left = ((-vw / 2f - offset.x) / total + bw / 2f).coerceIn(0f, maxOf(0f, bw - srcW))
                    val top = ((-vh / 2f - offset.y) / total + bh / 2f).coerceIn(0f, maxOf(0f, bh - srcH))

                    val cropW = srcW.toInt().coerceIn(1, workingBitmap.width - left.toInt())
                    val cropH = srcH.toInt().coerceIn(1, workingBitmap.height - top.toInt())
                    val cropped = Bitmap.createBitmap(workingBitmap, left.toInt(), top.toInt(), cropW, cropH)

                    val (targetW, targetH) = when (mode) {
                        CropActivity.MODE_COVER_16_9 -> 1280 to 720
                        CropActivity.MODE_WALLPAPER -> 720 to 1280
                        else -> 640 to 640
                    }
                    val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                    val actualHasAlpha = bgRemoved || bitmapHasTransparentCorner(scaled)
                    onDone(scaled, actualHasAlpha)
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00D66B),
                    contentColor = Color(0xFF05090B)
                )
            ) {
                Text("تأكيد وحفظ ✓", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private suspend fun selectUnwantedElementAt(
    source: Bitmap,
    tapX: Int,
    tapY: Int,
    colorTolerance: Int
): Pair<BooleanArray, Bitmap>? = withContext(Dispatchers.Default) {
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0) return@withContext null
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)

    fun isOpaque(i: Int): Boolean = ((pixels[i] ushr 24) and 0xFF) > 20

    // Snap within a small 14px neighborhood so tapping thin fragments works reliably
    var seedIdx = tapY.coerceIn(0, h - 1) * w + tapX.coerceIn(0, w - 1)
    if (!isOpaque(seedIdx)) {
        var bestDist = Int.MAX_VALUE
        var found = -1
        val r = 14
        val yMin = max(0, tapY - r)
        val yMax = min(h - 1, tapY + r)
        val xMin = max(0, tapX - r)
        val xMax = min(w - 1, tapX + r)
        for (yy in yMin..yMax) {
            for (xx in xMin..xMax) {
                val idx = yy * w + xx
                if (isOpaque(idx)) {
                    val d = (xx - tapX) * (xx - tapX) + (yy - tapY) * (yy - tapY)
                    if (d < bestDist) {
                        bestDist = d
                        found = idx
                    }
                }
            }
        }
        if (found < 0) return@withContext null
        seedIdx = found
    }

    var totalOpaque = 0
    for (i in pixels.indices) if (isOpaque(i)) totalOpaque++
    if (totalOpaque == 0) return@withContext null

    // 1. Check if tapped pixel belongs to an isolated detached island after BG removal
    val islandMask = BooleanArray(w * h)
    val q = ArrayDeque<Int>()
    islandMask[seedIdx] = true
    q.add(seedIdx)
    var islandCount = 0
    val islandLimit = (totalOpaque * 0.32f).toInt().coerceAtLeast(250)
    var hitLimit = false
    while (q.isNotEmpty()) {
        val cur = q.poll()!!
        islandCount++
        if (islandCount > islandLimit) {
            hitLimit = true
            break
        }
        val x = cur % w
        val y = cur / w
        if (x > 0 && !islandMask[cur - 1] && isOpaque(cur - 1)) { islandMask[cur - 1] = true; q.add(cur - 1) }
        if (x < w - 1 && !islandMask[cur + 1] && isOpaque(cur + 1)) { islandMask[cur + 1] = true; q.add(cur + 1) }
        if (y > 0 && !islandMask[cur - w] && isOpaque(cur - w)) { islandMask[cur - w] = true; q.add(cur - w) }
        if (y < h - 1 && !islandMask[cur + w] && isOpaque(cur + w)) { islandMask[cur + w] = true; q.add(cur + w) }
    }

    val chosenMask: BooleanArray
    var chosenCount: Int
    if (!hitLimit && islandCount > 0) {
        // Select the entire detached stray island cleanly in 1 tap!
        chosenMask = islandMask
        chosenCount = islandCount
    } else {
        // Connected to main foreground -> perform color-similarity flood fill around tapped object
        val seedColor = pixels[seedIdx]
        val sr = (seedColor shr 16) and 0xFF
        val sg = (seedColor shr 8) and 0xFF
        val sb = seedColor and 0xFF
        val mask = BooleanArray(w * h)
        q.clear()
        mask[seedIdx] = true
        q.add(seedIdx)
        chosenCount = 0
        val maxFlood = (w * h * 0.35f).toInt()
        while (q.isNotEmpty() && chosenCount < maxFlood) {
            val cur = q.poll()!!
            chosenCount++
            val x = cur % w
            val y = cur / w
            fun tryPush(ni: Int) {
                if (mask[ni] || !isOpaque(ni)) return
                val c = pixels[ni]
                val dr = abs(((c shr 16) and 0xFF) - sr)
                val dg = abs(((c shr 8) and 0xFF) - sg)
                val db = abs((c and 0xFF) - sb)
                if (dr + dg + db <= colorTolerance * 2) {
                    mask[ni] = true
                    q.add(ni)
                }
            }
            if (x > 0) tryPush(cur - 1)
            if (x < w - 1) tryPush(cur + 1)
            if (y > 0) tryPush(cur - w)
            if (y < h - 1) tryPush(cur + w)
        }
        chosenMask = mask
    }

    if (chosenCount <= 0) return@withContext null
    val overlay = buildRedOverlayBitmap(w, h, chosenMask)
    chosenMask to overlay
}

private suspend fun buildLassoSelectionMask(
    source: Bitmap,
    polygon: List<Offset>
): Pair<BooleanArray, Bitmap>? = withContext(Dispatchers.Default) {
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0 || polygon.size < 3) return@withContext null
    val n = polygon.size
    var minX = w - 1
    var maxX = 0
    var minY = h - 1
    var maxY = 0
    for (pt in polygon) {
        val px = pt.x.roundToInt().coerceIn(0, w - 1)
        val py = pt.y.roundToInt().coerceIn(0, h - 1)
        if (px < minX) minX = px
        if (px > maxX) maxX = px
        if (py < minY) minY = py
        if (py > maxY) maxY = py
    }

    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    val mask = BooleanArray(w * h)
    var selectedCount = 0
    val intersections = IntArray(n + 2)

    for (y in minY..maxY) {
        var count = 0
        val pyF = y + 0.5f
        var j = n - 1
        for (i in 0 until n) {
            val yi = polygon[i].y
            val yj = polygon[j].y
            if ((yi > pyF) != (yj > pyF)) {
                val xi = polygon[i].x
                val xj = polygon[j].x
                val xHit = xi + (pyF - yi) * (xj - xi) / (yj - yi)
                if (count < intersections.size) {
                    intersections[count++] = xHit.roundToInt().coerceIn(0, w - 1)
                }
            }
            j = i
        }
        if (count >= 2) {
            intersections.sort(0, count)
            var k = 0
            while (k + 1 < count) {
                val xStart = intersections[k].coerceIn(minX, maxX)
                val xEnd = intersections[k + 1].coerceIn(minX, maxX)
                for (x in xStart..xEnd) {
                    val idx = y * w + x
                    if (((pixels[idx] ushr 24) and 0xFF) > 10 && !mask[idx]) {
                        mask[idx] = true
                        selectedCount++
                    }
                }
                k += 2
            }
        }
    }

    if (selectedCount <= 0) return@withContext null
    val overlay = buildRedOverlayBitmap(w, h, mask)
    mask to overlay
}

private fun buildRedOverlayBitmap(w: Int, h: Int, mask: BooleanArray): Bitmap {
    val overlayPx = IntArray(w * h)
    val highlightColor = 0x99FF1744.toInt()
    for (i in mask.indices) {
        if (mask[i]) overlayPx[i] = highlightColor
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(overlayPx, 0, w, 0, 0, w, h)
    }
}

private fun bitmapHasTransparentCorner(bmp: Bitmap): Boolean {
    if (!bmp.hasAlpha() || bmp.width < 4 || bmp.height < 4) return false
    val w = bmp.width
    val h = bmp.height
    val samplePts = listOf(1 to 1, w - 2 to 1, 1 to h - 2, w - 2 to h - 2, w / 2 to 1)
    return samplePts.any { (x, y) -> (bmp.getPixel(x, y) ushr 24) < 240 }
}

private suspend fun removeBackgroundPreservingPerson(source: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
    val options = SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .build()
    val segmenter = Segmentation.getClient(options)
    val image = InputImage.fromBitmap(source, 0)
    val mask = suspendCancellableCoroutine<com.google.mlkit.vision.segmentation.SegmentationMask?> { cont ->
        segmenter.process(image)
            .addOnSuccessListener { segmentationMask ->
                if (cont.isActive) cont.resume(segmentationMask)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
    } ?: run {
        runCatching { segmenter.close() }
        return@withContext null
    }

    val maskBuffer = mask.buffer
    val maskW = mask.width
    val maskH = mask.height
    val w = source.width
    val h = source.height
    val argb = source.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(w * h)
    argb.getPixels(pixels, 0, w, 0, 0, w, h)

    maskBuffer.rewind()
    val confidences = FloatArray(maskW * maskH)
    for (i in confidences.indices) {
        confidences[i] = maskBuffer.float
    }
    runCatching { segmenter.close() }

    var foregroundCount = 0
    for (y in 0 until h) {
        val my = (y * maskH) / h
        for (x in 0 until w) {
            val mx = (x * maskW) / w
            val conf = confidences[my * maskW + mx]
            val idx = y * w + x
            val p = pixels[idx]
            val alpha = when {
                conf >= 0.72f -> {
                    foregroundCount++
                    (p ushr 24) and 0xFF
                }
                conf <= 0.28f -> 0
                else -> {
                    val t = ((conf - 0.28f) / 0.44f).coerceIn(0f, 1f)
                    (((p ushr 24) and 0xFF) * t).toInt()
                }
            }
            pixels[idx] = (alpha shl 24) or (p and 0x00FFFFFF)
        }
    }

    if (foregroundCount < (w * h) / 100) {
        // Too small / no clear person detected; return null rather than blanking the photo
        return@withContext null
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    out
}
