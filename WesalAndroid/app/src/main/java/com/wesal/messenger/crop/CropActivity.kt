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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlin.coroutines.resume
import kotlin.math.hypot
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
    var touchCursor by remember { mutableStateOf<Offset?>(null) }
    val undoStack = remember { mutableStateListOf<Bitmap>() }

    val img = remember(workingBitmap, bitmapVersion) { workingBitmap.asImageBitmap() }
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

    fun pushUndoSnapshot() {
        if (undoStack.size >= 12) undoStack.removeAt(0)
        undoStack.add(workingBitmap.copy(Bitmap.Config.ARGB_8888, true))
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
                CropToolMode.ERASE -> "مرّر إصبعك لمسح الخلفية يدوياً بدقة"
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
                .fillMaxHeight(0.55f)
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
                .pointerInput(viewportSize, toolMode, zoomLevel, offset, brushSizePx) {
                    if (toolMode == CropToolMode.MOVE) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val vw = viewportSize.width.toFloat()
                            val vh = viewportSize.height.toFloat()
                            val nz = (zoomLevel * zoom).coerceIn(1f, 6f)
                            zoomLevel = nz
                            offset = clampOffset(offset + pan, nz, vw, vh)
                        }
                    } else {
                        detectDragGestures(
                            onDragStart = { start ->
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

        bgError?.let { err ->
            Text(
                text = err,
                color = Color(0xFFFF6E87),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Manual touch brush mode selector + Undo + Glow preview toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
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
                selected = toolMode == CropToolMode.ERASE,
                onClick = { toolMode = CropToolMode.ERASE },
                leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("ممحاة", maxLines = 1) }
            )
            FilterChip(
                selected = toolMode == CropToolMode.RESTORE,
                onClick = { toolMode = CropToolMode.RESTORE },
                leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("استعادة", maxLines = 1) }
            )
            if (undoStack.isNotEmpty()) {
                IconButton(
                    onClick = {
                        val last = undoStack.removeAt(undoStack.lastIndex)
                        workingBitmap = last
                        bitmapVersion++
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "تراجع", tint = Color(0xFF00D5E8))
                }
            }
        }

        if (toolMode != CropToolMode.MOVE) {
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
                            } else {
                                bgError = "تعذر عزل الشخص تلقائياً؛ استخدم أداة الممحاة اليدوية أو جرب صورة أوضح"
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
