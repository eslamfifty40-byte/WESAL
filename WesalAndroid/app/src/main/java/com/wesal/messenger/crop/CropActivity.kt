package com.wesal.messenger.crop

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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.File

class CropActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data
        val bmp = uri?.let { loadBitmap(it) }
        if (bmp == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        setContent {
            CropScreen(
                bmp = bmp,
                onCancel = { finish() },
                onDone = { out ->
                    val f = File(cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                    f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    setResult(RESULT_OK, Intent().setData(Uri.fromFile(f)))
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
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val rot = contentResolver.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
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
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun CropScreen(bmp: Bitmap, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    var zoomLevel by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewPx by remember { mutableStateOf(0f) }
    val bw = bmp.width.toFloat()
    val bh = bmp.height.toFloat()

    fun base(): Float = if (viewPx > 0f) viewPx / minOf(bw, bh) else 1f
    fun clampOffset(o: Offset, z: Float): Offset {
        val total = base() * z
        val mx = maxOf(0f, (bw * total - viewPx) / 2f)
        val my = maxOf(0f, (bh * total - viewPx) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("حرّك الصورة وكبّرها بصباعين", color = Color.White, modifier = Modifier.padding(16.dp))
        Spacer(Modifier.weight(1f))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val side = maxWidth
            val sidePx = with(LocalDensity.current) { side.toPx() }
            LaunchedEffect(sidePx) { viewPx = sidePx }
            Canvas(
                Modifier.size(side).clipToBounds().pointerInput(sidePx) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nz = (zoomLevel * zoom).coerceIn(1f, 6f)
                        zoomLevel = nz
                        offset = clampOffset(offset + pan, nz)
                    }
                }
            ) {
                val total = base() * zoomLevel
                withTransform({
                    translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
                    scale(total, total, pivot = Offset.Zero)
                    translate(-bw / 2f, -bh / 2f)
                }) { drawImage(img) }
                drawCircle(Color.White, radius = size.minDimension / 2f, style = Stroke(3f))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(54.dp)) {
                Text("إلغاء", color = Color.White)
            }
            Button(
                onClick = {
                    val total = base() * zoomLevel
                    val size = viewPx / total
                    val left = ((-viewPx / 2f - offset.x) / total + bw / 2f).coerceIn(0f, maxOf(0f, bw - size))
                    val top = ((-viewPx / 2f - offset.y) / total + bh / 2f).coerceIn(0f, maxOf(0f, bh - size))
                    val s = size.toInt().coerceAtLeast(1)
                    val crop = Bitmap.createBitmap(bmp, left.toInt(), top.toInt(), minOf(s, bmp.width - left.toInt()), minOf(s, bmp.height - top.toInt()))
                    onDone(Bitmap.createScaledBitmap(crop, 512, 512, true))
                },
                modifier = Modifier.weight(1f).height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853), contentColor = Color.Black)
            ) {
                Text("تأكيد ✓")
            }
        }
    }
}
