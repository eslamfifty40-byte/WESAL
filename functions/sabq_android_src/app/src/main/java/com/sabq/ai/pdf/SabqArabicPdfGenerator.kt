package com.sabq.ai.pdf

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.sabq.ai.R
import com.sabq.ai.data.local.HealthRiskAssessmentEntity
import com.sabq.ai.data.local.HealthScanEntity
import com.sabq.ai.data.local.LabReadingEntity
import com.sabq.ai.data.local.UserProfileEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates native Arabic RTL PDF reports using Android StaticLayout + TextDirectionHeuristics.RTL
 * with SABQ official logo, structured RTL tables, embedded session reference images (including hair crop),
 * and strictly English digits (0-9).
 */
class SabqArabicPdfGenerator(private val context: Context) {

    fun generateComprehensivePdf(
        user: UserProfileEntity,
        scans: List<HealthScanEntity>,
        labReadings: List<LabReadingEntity>,
        riskAssessments: List<HealthRiskAssessmentEntity> = emptyList(),
        previousReportSummary: String? = null,
        reportTitleAr: String = "تقرير سَبَق الصحي الشامل — SABQ AI",
        referenceImagePaths: List<String> = emptyList()
    ): File {
        val doc = PdfDocument()
        val pageWidth = 595 // A4 points
        val pageHeight = 842
        val margin = 38f
        val contentWidth = (pageWidth - margin * 2).toInt()

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin

        val bgPaint = Paint().apply { color = Color.parseColor("#0B0D18") }
        val cardPaint = Paint().apply { color = Color.parseColor("#12162A") }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#334155")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val headerBandPaint = Paint().apply { color = Color.parseColor("#161B33") }

        fun drawPageShell() {
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), bgPaint)
        }
        drawPageShell()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00D4FF")
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A78BFA")
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 10f
        }
        val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 9.2f
        }

        fun ensureSpace(neededHeight: Float) {
            if (y + neededHeight > pageHeight - margin - 18f) {
                doc.finishPage(page)
                pageNum += 1
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                drawPageShell()
                y = margin
            }
        }

        fun drawRtlBlock(text: String, paint: TextPaint, spacingAfter: Float = 8f) {
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setLineSpacing(3.5f, 1.15f)
                .build()
            ensureSpace(layout.height + spacingAfter)
            canvas.save()
            canvas.translate(margin, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + spacingAfter
        }

        fun drawRtlTable(headers: List<String>, rows: List<List<String>>) {
            if (headers.isEmpty()) return
            val colCount = headers.size
            val colWidth = contentWidth.toFloat() / colCount
            val rowPad = 6f

            fun drawRow(cells: List<String>, isHeader: Boolean) {
                val layouts = cells.map { cell ->
                    StaticLayout.Builder
                        .obtain(cell, 0, cell.length, if (isHeader) headingPaint else bodyPaint, (colWidth - rowPad * 2).toInt().coerceAtLeast(30))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setTextDirection(TextDirectionHeuristics.RTL)
                        .setLineSpacing(2f, 1.1f)
                        .build()
                }
                val rowH = (layouts.maxOfOrNull { it.height } ?: 16) + rowPad * 2
                ensureSpace(rowH + 4f)
                val bg = if (isHeader) headerBandPaint else cardPaint
                canvas.drawRect(margin, y, margin + contentWidth, y + rowH, bg)
                canvas.drawRect(margin, y, margin + contentWidth, y + rowH, borderPaint)

                // RTL columns drawn right-to-left
                for (i in 0 until colCount) {
                    val colLeft = margin + contentWidth - (i + 1) * colWidth
                    if (i > 0) {
                        canvas.drawLine(colLeft + colWidth, y, colLeft + colWidth, y + rowH, borderPaint)
                    }
                    canvas.save()
                    canvas.translate(colLeft + rowPad, y + rowPad)
                    layouts[i].draw(canvas)
                    canvas.restore()
                }
                y += rowH
            }

            drawRow(headers, isHeader = true)
            rows.forEach { drawRow(it, isHeader = false) }
            y += 10f
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val nowStr = dateFormat.format(Date())

        // Top Brand Header with official SABQ icon
        ensureSpace(74f)
        canvas.drawRoundRect(RectF(margin, y, pageWidth - margin, y + 66f), 12f, 12f, headerBandPaint)
        canvas.drawRoundRect(RectF(margin, y, pageWidth - margin, y + 66f), 12f, 12f, borderPaint)

        BitmapFactory.decodeResource(context.resources, R.drawable.sabq_official_icon)?.let { logoBmp ->
            val logoRect = RectF(pageWidth - margin - 56f, y + 9f, pageWidth - margin - 10f, y + 55f)
            canvas.drawBitmap(logoBmp, null, logoRect, null)
        }

        val headerTitleLayout = StaticLayout.Builder
            .obtain(reportTitleAr, 0, reportTitleAr.length, titlePaint, contentWidth - 76)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .build()
        canvas.save()
        canvas.translate(margin + 10f, y + 10f)
        headerTitleLayout.draw(canvas)
        canvas.restore()

        val metaText = "المستخدم: ${user.displayName}  |  التاريخ: $nowStr"
        val metaLayout = StaticLayout.Builder
            .obtain(metaText, 0, metaText.length, mutedPaint, contentWidth - 76)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .build()
        canvas.save()
        canvas.translate(margin + 10f, y + 40f)
        metaLayout.draw(canvas)
        canvas.restore()
        y += 78f

        // Embedded Reference Photos (Face / Body / Hair from the scan session)
        val collectedImagePaths = (referenceImagePaths + scans.mapNotNull { it.savedImagePath })
            .distinct()
            .filter { File(it).exists() }
            .take(3)

        if (collectedImagePaths.isNotEmpty()) {
            drawRtlBlock("الصور المرجعية الملتقطة في جلسة الفحص:", headingPaint, 4f)
            ensureSpace(130f)
            var imgX = pageWidth - margin - 150f
            collectedImagePaths.forEach { path ->
                BitmapFactory.decodeFile(path)?.let { bmp ->
                    val rect = RectF(imgX, y, imgX + 144f, y + 112f)
                    canvas.drawBitmap(bmp, null, rect, null)
                    canvas.drawRect(rect, borderPaint)
                    imgX -= 158f
                }
            }
            y += 126f
        }

        // Section 1: Summary Table of Scans
        drawRtlBlock("1. ملخص الفحوص المنجزة فعليًا:", headingPaint, 4f)
        if (scans.isEmpty() && labReadings.isEmpty() && riskAssessments.isEmpty()) {
            drawRtlBlock("لم تُسجل فحوص أو قراءات موثقة في هذا التقرير حتى الآن.", bodyPaint, 10f)
        } else if (scans.isNotEmpty()) {
            drawRtlTable(
                headers = listOf("القسم", "المؤشر / الملخص", "الحالة", "درجة الثقة"),
                rows = scans.map {
                    listOf(
                        it.scanType,
                        it.summary.take(72),
                        it.severityLevel,
                        it.confidenceLabel
                    )
                }
            )
            scans.forEach { scan ->
                val tag = if (scan.isNonDiagnosticEstimate) " [تقدير بصري/حسي غير تشخيصي]" else ""
                drawRtlBlock("تفاصيل ${scan.scanType}$tag", headingPaint, 3f)
                drawRtlBlock(
                    "الملخص: ${scan.summary}\n" +
                        "مصدر القراءة: ${scan.sourceDescription}\n" +
                        "المشكلات المحتملة: ${scan.potentialIssues}\n" +
                        "حدود الدقة: ${scan.limitations}\n" +
                        "التوصيات: ${scan.recommendations}\n" +
                        "متى تجب مراجعة الطبيب: ${scan.doctorWarning}",
                    bodyPaint,
                    10f
                )
            }
        }

        // Section 2: Voluntary Risk Assessments
        if (riskAssessments.isNotEmpty()) {
            drawRtlBlock("2. التقييم الصحي التقديري (عوامل الخطورة — ليس تشخيصًا):", headingPaint, 4f)
            drawRtlTable(
                headers = listOf("المحور الصحي", "مستوى الخطورة", "الفحوص المخبرية المقترحة"),
                rows = riskAssessments.map {
                    listOf(it.categoryTitleAr, it.riskLevel, it.recommendedTestsAr)
                }
            )
            riskAssessments.forEach { item ->
                drawRtlBlock("${item.categoryTitleAr}: ${item.rationaleAr}\nالتوجيه: ${item.suggestionsAr}", bodyPaint, 6f)
            }
        }

        // Section 3: Verified Lab / Vital Readings
        if (labReadings.isNotEmpty()) {
            drawRtlBlock("3. القراءات والتحاليل الموثقة:", headingPaint, 4f)
            drawRtlTable(
                headers = listOf("الفحص", "القيمة", "المصدر", "التاريخ"),
                rows = labReadings.map {
                    listOf(it.testNameAr, "${it.measuredValue} ${it.unit}", it.source, dateFormat.format(Date(it.recordedAt)))
                }
            )
        }

        // Section 4: Comparison with Previous Reports
        drawRtlBlock("4. المقارنة مع التقارير السابقة:", headingPaint, 4f)
        drawRtlBlock(
            previousReportSummary ?: "لا توجد تقارير سابقة من نفس الفئة للمقارنة بعد.",
            bodyPaint,
            10f
        )

        drawRtlBlock(
            "إخلاء مسؤولية طبي: كافة التقديرات البصرية والضوئية وتقييمات عوامل الخطورة في سَبَق استرشادية غير تشخيصية، ولا تغني عن المختبر أو استشارة الطبيب.",
            mutedPaint,
            6f
        )

        doc.finishPage(page)

        val reportsDir = File(context.filesDir, "reports").apply { mkdirs() }
        val outFile = File(reportsDir, "SABQ_Report_${user.userId}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة التقرير الصحي PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun printPdf(file: File) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print("SABQ_Health_Report", object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback,
                extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = android.print.PrintDocumentInfo.Builder(file.name)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>,
                destination: android.os.ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback
            ) {
                runCatching {
                    file.inputStream().use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                }.onFailure {
                    callback.onWriteFailed(it.message)
                }
            }
        }, null)
    }
}
