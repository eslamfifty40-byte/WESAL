package com.sabq.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabq.ai.SabqApplication
import com.sabq.ai.ai.SabqHealthAssessmentEngine
import com.sabq.ai.ai.VoluntaryHealthIntake
import com.sabq.ai.data.local.ReportRecordEntity
import com.sabq.ai.data.local.UserProfileEntity
import com.sabq.ai.ui.theme.SabqColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SmartHealthAssessmentScreen(app: SabqApplication, userId: String) {
    val dao = app.database.sabqDao()
    val scope = rememberCoroutineScope()
    val labs by dao.observeLabReadings(userId).collectAsState(initial = emptyList())
    val assessments by dao.observeRiskAssessments(userId).collectAsState(initial = emptyList())

    var ageText by remember { mutableStateOf("") }
    var genderAr by remember { mutableStateOf("ذكر") }
    var heightText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var activityLevel by remember { mutableStateOf("متوسط") }
    var sleepText by remember { mutableStateOf("7") }
    var selectedSymptoms by remember { mutableStateOf(setOf<String>()) }
    var selectedFamily by remember { mutableStateOf(setOf<String>()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val symptomOptions = listOf("عطش وتبول متكرر", "إرهاق وتعب", "دوخة أو صداع", "خفقان أو ضيق نفس", "تساقط شعر أو شحوب")
    val familyOptions = listOf("السكري", "الضغط", "أمراض القلب", "الغدة الدرقية", "الأنيميا")

    val liveBmi = SabqHealthAssessmentEngine.calculateRealBmi(heightText.toFloatOrNull(), weightText.toFloatOrNull())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("التقييم الصحي التقديري (عوامل الخطورة)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Surface(
            color = SabqColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SabqColors.Cyan.copy(alpha = 0.35f))
        ) {
            Text(
                "تقييم عوامل خطورة مبني على مدخلاتك الطوعية والتحاليل الموثقة — وليس تشخيصًا طبيًا ولا يعرض أي أرقام ضغط أو سكر وهمية.",
                fontSize = 12.sp,
                color = SabqColors.Cyan,
                modifier = Modifier.padding(12.dp)
            )
        }

        OutlinedTextField(value = ageText, onValueChange = { ageText = it }, label = { Text("العمر (اختياري)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ذكر", "أنثى").forEach { g ->
                FilterChip(selected = genderAr == g, onClick = { genderAr = g }, label = { Text(g) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = heightText, onValueChange = { heightText = it }, label = { Text("الطول (سم)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = weightText, onValueChange = { weightText = it }, label = { Text("الوزن (كجم)") }, modifier = Modifier.weight(1f))
        }
        Text("مؤشر كتلة الجسم الفعلي (BMI): ${SabqHealthAssessmentEngine.classifyBmiAr(liveBmi)}", fontSize = 12.sp, color = SabqColors.Emerald)

        Text("الأعراض الطوعية:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            symptomOptions.forEach { sym ->
                val checked = selectedSymptoms.contains(sym)
                Row {
                    Checkbox(checked = checked, onCheckedChange = {
                        selectedSymptoms = if (checked) selectedSymptoms - sym else selectedSymptoms + sym
                    })
                    Text(sym, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        Text("التاريخ العائلي أو الشخصي:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            familyOptions.forEach { fam ->
                val checked = selectedFamily.contains(fam)
                Row {
                    Checkbox(checked = checked, onCheckedChange = {
                        selectedFamily = if (checked) selectedFamily - fam else selectedFamily + fam
                    })
                    Text(fam, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    val intake = VoluntaryHealthIntake(
                        ageYears = ageText.toIntOrNull(),
                        genderAr = genderAr,
                        heightCm = heightText.toFloatOrNull(),
                        weightKg = weightText.toFloatOrNull(),
                        symptoms = selectedSymptoms.toList(),
                        personalHistory = selectedFamily.toList(),
                        familyHistory = selectedFamily.toList(),
                        activityLevel = activityLevel,
                        sleepHours = sleepText.toFloatOrNull()
                    )
                    val results = SabqHealthAssessmentEngine.evaluateRiskFactors(userId, intake, labs)
                    results.forEach { dao.insertRiskAssessment(it) }

                    val user = dao.getUser(userId) ?: UserProfileEntity(userId, "المستخدم")
                    val pdfFile = app.pdfGenerator.generateComprehensivePdf(
                        user = user,
                        scans = emptyList(),
                        labReadings = labs,
                        riskAssessments = results.take(5),
                        reportTitleAr = "تقرير التقييم الصحي التقديري وعوامل الخطورة — SABQ AI"
                    )
                    dao.insertReport(
                        ReportRecordEntity(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            reportType = "RISK_DIABETES",
                            title = "تقرير التقييم الصحي التقديري (5 محاور)",
                            pdfFilePath = pdfFile.absolutePath,
                            scanIdsIncluded = results.joinToString(",") { it.id }
                        )
                    )
                    statusMessage = "تم حساب عوامل الخطورة وحفظ التقرير المستقل بنجاح."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("تحليل عوامل الخطورة وحفظ التقرير")
        }

        statusMessage?.let { Text(it, color = SabqColors.Emerald, fontSize = 12.sp) }

        assessments.take(5).forEach { item ->
            Surface(
                color = SabqColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SabqColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.categoryTitleAr, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Text("مستوى الخطورة: ${item.riskLevel}", color = SabqColors.Cyan, fontSize = 12.sp)
                    Text(item.rationaleAr, color = SabqColors.TextMuted, fontSize = 11.sp)
                    Text("الفحص المقترح: ${item.recommendedTestsAr}", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}
