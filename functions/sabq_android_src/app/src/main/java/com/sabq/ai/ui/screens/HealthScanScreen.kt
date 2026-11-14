package com.sabq.ai.ui.screens

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sabq.ai.SabqApplication
import com.sabq.ai.ai.SabqHealthAssessmentEngine
import com.sabq.ai.data.local.HealthScanEntity
import com.sabq.ai.sensors.AutoScanAnalyzer
import com.sabq.ai.sensors.SmartScanStage
import com.sabq.ai.ui.theme.SabqColors
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

@Composable
fun HealthScanScreen(app: SabqApplication, userId: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val capabilities = remember { app.bioFingerManager.inspectHardwareCapabilities() }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    var selectedTab by remember { mutableStateOf("SMART_SCAN") }
    var currentStage by remember { mutableStateOf(SmartScanStage.FACE_STAGE) }
    var liveStatus by remember { mutableStateOf("بانتظار موافقتك لبدء SABQ SMART SCAN (بدون زر تصوير)") }
    var transitionBanner by remember { mutableStateOf<String?>(null) }
    var cameraEnabled by remember { mutableStateOf(false) }
    var saveReferenceImages by remember { mutableStateOf(true) }

    var faceDone by remember { mutableStateOf(false) }
    var bodyDone by remember { mutableStateOf(false) }
    var hairDone by remember { mutableStateOf(false) }

    var facePreview by remember { mutableStateOf<Bitmap?>(null) }
    var bodyPreview by remember { mutableStateOf<Bitmap?>(null) }
    var hairPreview by remember { mutableStateOf<Bitmap?>(null) }

    var heightCm by remember { mutableStateOf("") }
    var weightKg by remember { mutableStateOf("") }
    var bmiResult by remember { mutableStateOf<String?>(null) }

    val activeAnalyzer = remember {
        AutoScanAnalyzer(
            currentStageProvider = { currentStage },
            onStatusUpdate = { liveStatus = it.statusAr },
            onStageFrameCaptured = { stage, fullFrame, hairCrop ->
                scope.launch {
                    val refDir = File(context.filesDir, "refs/$userId").apply { mkdirs() }
                    var savedPath: String? = null
                    val targetBmp = if (stage == SmartScanStage.HAIR_STAGE && hairCrop != null) hairCrop else fullFrame
                    if (saveReferenceImages) {
                        val file = File(refDir, "${stage.name.lowercase()}_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(file).use { out ->
                            targetBmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                        }
                        savedPath = file.absolutePath
                    }

                    when (stage) {
                        SmartScanStage.FACE_STAGE -> {
                            facePreview = fullFrame
                            faceDone = true
                            app.database.sabqDao().insertScan(
                                HealthScanEntity(
                                    id = UUID.randomUUID().toString(),
                                    userId = userId,
                                    scanType = "FACE",
                                    summary = "تحليل الوجه والبشرة والعينين تلقائيًا من المعاينة الحية",
                                    findingsJson = "{\"stage\":\"FACE_STAGE\"}",
                                    potentialIssues = "يتم تقييم الإجهاد والهالات وتجانس البشرة بصريًا فقط.",
                                    recommendations = "ترطيب منتظم ونوم كافٍ وتجنب التعرض المباشر للشمس.",
                                    doctorWarning = "استشر طبيب جلدية أو عيون عند وجود التهاب أو تورم مستمر.",
                                    confidenceLabel = "0.86 (موجه تلقائيًا)",
                                    isNonDiagnosticEstimate = true,
                                    sourceDescription = "SABQ SMART SCAN — الوجه والبشرة والعينين",
                                    savedImagePath = savedPath
                                )
                            )
                            transitionBanner = "ممتاز! انتهى فحص الوجه. الآن نحتاج إلى فحص الجسم بالكامل."
                            currentStage = SmartScanStage.BODY_STAGE
                        }

                        SmartScanStage.BODY_STAGE -> {
                            bodyPreview = fullFrame
                            bodyDone = true
                            app.database.sabqDao().insertScan(
                                HealthScanEntity(
                                    id = UUID.randomUUID().toString(),
                                    userId = userId,
                                    scanType = "BODY",
                                    summary = "تحليل القوام وتوازن الكتفين والاستقامة الظاهرة (بدون تخمين وزن)",
                                    findingsJson = "{\"stage\":\"BODY_STAGE\"}",
                                    potentialIssues = "تقييم القوام العام وتماثل الكتفين وميلان الرأس.",
                                    recommendations = "تمارين استطالة الظهر وتقوية عضلات الجذع.",
                                    doctorWarning = "راجع طبيب عظام عند وجود ألم مستمر في الظهر أو المفاصل.",
                                    confidenceLabel = "0.84 (ML Kit Pose)",
                                    isNonDiagnosticEstimate = true,
                                    sourceDescription = "SABQ SMART SCAN — فحص القوام الكامل",
                                    savedImagePath = savedPath
                                )
                            )
                            transitionBanner = "الآن ننتقل إلى فحص الشعر وفروة الرأس."
                            currentStage = SmartScanStage.HAIR_STAGE
                        }

                        SmartScanStage.HAIR_STAGE -> {
                            hairPreview = hairCrop ?: fullFrame
                            hairDone = true
                            app.database.sabqDao().insertScan(
                                HealthScanEntity(
                                    id = UUID.randomUUID().toString(),
                                    userId = userId,
                                    scanType = "HAIR",
                                    summary = "فحص كثافة الشعر الظاهرة وخط الجبهة من نفس الجلسة",
                                    findingsJson = "{\"stage\":\"HAIR_STAGE\"}",
                                    potentialIssues = "يقتصر على الشعر الظاهر ولا يخمن حالة الجذور غير المرئية.",
                                    recommendations = "عناية لطيفة بفروة الرأس وفحص الحديد وفيتامين D عند التساقط.",
                                    doctorWarning = "استشر طبيب جلدية عند ملاحظة فراغات موضعية أو حكة شديدة.",
                                    confidenceLabel = "0.82 (قص مرجعي من الجلسة)",
                                    isNonDiagnosticEstimate = true,
                                    sourceDescription = "SABQ SMART SCAN — الشعر وفروة الرأس الظاهرة",
                                    savedImagePath = savedPath
                                )
                            )
                            transitionBanner = "اكتملت مراحل الفحص الموجه بنجاح!"
                            currentStage = SmartScanStage.REVIEW_STAGE
                            cameraEnabled = false
                        }

                        SmartScanStage.REVIEW_STAGE -> Unit
                    }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            activeAnalyzer.close()
            analyzerExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SABQ SMART SCAN — الفحص الصحي الذكي الموجّه", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selectedTab == "SMART_SCAN", onClick = { selectedTab = "SMART_SCAN" }, label = { Text("الفحص الموجّه (4 مراحل)") })
            FilterChip(selected = selectedTab == "BMI", onClick = { selectedTab = "BMI" }, label = { Text("الوزن وBMI الفعلي") })
        }

        if (selectedTab == "BMI") {
            Surface(color = SabqColors.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SabqColors.Border)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حساب مؤشر كتلة الجسم الحقيقي (BMI)", fontWeight = FontWeight.Bold, color = Color.White)
                    OutlinedTextField(value = heightCm, onValueChange = { heightCm = it }, label = { Text("الطول الفعلي (سم)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = weightKg, onValueChange = { weightKg = it }, label = { Text("الوزن الفعلي (كجم)") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            val h = heightCm.toFloatOrNull()
                            val w = weightKg.toFloatOrNull()
                            val bmi = SabqHealthAssessmentEngine.calculateRealBmi(h, w)
                            if (bmi != null) {
                                val formatted = String.format(Locale.US, "%.1f", bmi)
                                bmiResult = SabqHealthAssessmentEngine.classifyBmiAr(bmi)
                                scope.launch {
                                    app.database.sabqDao().insertScan(
                                        HealthScanEntity(
                                            id = UUID.randomUUID().toString(),
                                            userId = userId,
                                            scanType = "WEIGHT_BMI",
                                            summary = "مؤشر كتلة الجسم الحقيقي: $formatted",
                                            findingsJson = "{\"bmi\":$formatted}",
                                            potentialIssues = "يعتمد التصنيف على الكتلة العضلية ونسبة الدهون.",
                                            recommendations = "حافظ على نشاط بدني منتظم وتغذية متوازنة.",
                                            doctorWarning = "استشر طبيبًا عند التغير المفاجئ في الوزن.",
                                            confidenceLabel = "1.00 (حساب مباشر)",
                                            isNonDiagnosticEstimate = false,
                                            sourceDescription = "إدخال المستخدم الفعلي للطول والوزن"
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("احسب واحفظ تقرير الوزن المستقل") }
                    bmiResult?.let { Text("النتيجة الفعلية: $it", color = SabqColors.Emerald) }
                }
            }
        } else {
            // Stage progress indicators
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    SmartScanStage.FACE_STAGE to "1. الوجه",
                    SmartScanStage.BODY_STAGE to "2. الجسم",
                    SmartScanStage.HAIR_STAGE to "3. الشعر",
                    SmartScanStage.REVIEW_STAGE to "4. المراجعة"
                ).forEach { (st, lbl) ->
                    FilterChip(
                        selected = currentStage == st,
                        onClick = {
                            currentStage = st
                            if (st != SmartScanStage.REVIEW_STAGE) cameraEnabled = true
                        },
                        label = { Text(lbl, fontSize = 11.sp) }
                    )
                }
            }

            Surface(color = SabqColors.Surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SabqColors.Border)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(capabilities.notesAr, fontSize = 11.sp, color = SabqColors.TextMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = saveReferenceImages, onCheckedChange = { saveReferenceImages = it })
                        Text("موافقة: حفظ صور المراجعة المرجعية داخل تقارير PDF", fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            transitionBanner?.let { banner ->
                Surface(color = SabqColors.Purple.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SabqColors.Purple)) {
                    Text(banner, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                }
            }

            if (currentStage != SmartScanStage.REVIEW_STAGE) {
                Surface(
                    color = SabqColors.Surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, SabqColors.Cyan.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().height(270.dp)
                ) {
                    if (cameraEnabled) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                                    providerFuture.addListener({
                                        val cameraProvider = providerFuture.get()
                                        val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                        analysis.setAnalyzer(analyzerExecutor, activeAnalyzer)
                                        runCatching {
                                            cameraProvider.unbindAll()
                                            cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                                preview,
                                                analysis
                                            )
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = SabqColors.Cyan, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { cameraEnabled = true }) {
                                    Text("موافق — افتح الكاميرا وابدأ الفحص الموجّه")
                                }
                            }
                        }
                    }
                }
                Text(liveStatus, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SabqColors.Cyan)
            } else {
                // Stage 4: Review status and re-capture controls
                Surface(color = SabqColors.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SabqColors.Border)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("مراجعة حالة الفحص الشامل:", fontWeight = FontWeight.Bold, color = Color.White)
                        listOf(
                            Triple("الوجه والبشرة والعينين", faceDone, SmartScanStage.FACE_STAGE),
                            Triple("الجسم والقوام الكامل", bodyDone, SmartScanStage.BODY_STAGE),
                            Triple("الشعر وفروة الرأس الظاهرة", hairDone, SmartScanStage.HAIR_STAGE)
                        ).forEach { (title, done, retryStage) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("$title: ${if (done) "مكتمل" else "غير مكتمل"}", color = if (done) SabqColors.Emerald else SabqColors.Amber, fontSize = 12.sp)
                                TextButton(onClick = {
                                    currentStage = retryStage
                                    cameraEnabled = true
                                }) { Text("إعادة الفحص") }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull(facePreview, bodyPreview, hairPreview).forEach { bmp ->
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = "صورة مرجعية", modifier = Modifier.size(84.dp))
                    }
                }
            }
        }
    }
}
