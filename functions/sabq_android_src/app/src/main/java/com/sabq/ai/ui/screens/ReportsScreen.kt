package com.sabq.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabq.ai.SabqApplication
import com.sabq.ai.data.local.ReportRecordEntity
import com.sabq.ai.data.local.UserProfileEntity
import com.sabq.ai.ui.theme.SabqColors
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val REPORT_FILTERS = listOf(
    "COMPREHENSIVE" to "التقرير الشامل",
    "FACE" to "تقرير الوجه",
    "SKIN" to "تقرير البشرة",
    "EYE" to "تقرير العين",
    "HAIR" to "تقرير الشعر",
    "BODY" to "تقرير الجسم والقوام",
    "PULSE" to "تقرير النبض",
    "RESPIRATORY" to "تقرير التنفس",
    "RISK_DIABETES" to "مخاطر السكر",
    "RISK_BP" to "مخاطر الضغط",
    "RISK_THYROID_BLOOD" to "الغدة والدم",
    "WEIGHT_BMI" to "الوزن وBMI"
)

@Composable
fun ReportsScreen(app: SabqApplication, userId: String) {
    val dao = app.database.sabqDao()
    val scope = rememberCoroutineScope()
    val scans by dao.observeScans(userId).collectAsState(initial = emptyList())
    val labs by dao.observeLabReadings(userId).collectAsState(initial = emptyList())
    val risks by dao.observeRiskAssessments(userId).collectAsState(initial = emptyList())
    val reports by dao.observeReports(userId).collectAsState(initial = emptyList())
    var selectedType by remember { mutableStateOf("COMPREHENSIVE") }
    var statusText by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("التقارير الصحية المستقلة (12 تقرير PDF عربي)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            REPORT_FILTERS.forEach { (code, label) ->
                FilterChip(
                    selected = selectedType == code,
                    onClick = { selectedType = code },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        Button(
            onClick = {
                scope.launch {
                    val user = dao.getUser(userId) ?: UserProfileEntity(userId, "المستخدم")
                    val filteredScans = if (selectedType == "COMPREHENSIVE") scans else scans.filter { it.scanType == selectedType || (selectedType == "FACE" && it.scanType == "FACE") }
                    val filteredRisks = if (selectedType == "COMPREHENSIVE") risks else risks.filter { it.category == selectedType }
                    val labelAr = REPORT_FILTERS.firstOrNull { it.first == selectedType }?.second ?: "تقرير سَبَق الصحي"
                    val prev = reports.firstOrNull { it.reportType == selectedType }?.title

                    val file = app.pdfGenerator.generateComprehensivePdf(
                        user = user,
                        scans = filteredScans,
                        labReadings = labs,
                        riskAssessments = filteredRisks,
                        previousReportSummary = prev,
                        reportTitleAr = "$labelAr — SABQ AI"
                    )
                    dao.insertReport(
                        ReportRecordEntity(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            reportType = selectedType,
                            title = "$labelAr (${filteredScans.size} فحوص)",
                            pdfFilePath = file.absolutePath,
                            scanIdsIncluded = filteredScans.joinToString(",") { it.id }
                        )
                    )
                    statusText = "تم إنشاء $labelAr بصيغة PDF عربية متصلة وحفظه في سجل المستخدم."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("إنشاء تقرير PDF للفئة المحددة وحفظه")
        }

        statusText?.let { Text(it, color = SabqColors.Emerald, fontSize = 12.sp) }

        val visibleReports = if (selectedType == "COMPREHENSIVE") reports else reports.filter { it.reportType == selectedType }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleReports) { report ->
                Surface(
                    color = SabqColors.Surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SabqColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(report.title, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val f = File(report.pdfFilePath)
                                if (f.exists()) app.pdfGenerator.sharePdf(f)
                            }) { Text("مشاركة PDF") }
                            OutlinedButton(onClick = {
                                val f = File(report.pdfFilePath)
                                if (f.exists()) app.pdfGenerator.printPdf(f)
                            }) { Text("طباعة") }
                        }
                    }
                }
            }
        }
    }
}
