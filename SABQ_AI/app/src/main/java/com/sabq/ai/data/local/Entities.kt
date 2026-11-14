package com.sabq.ai.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MemoryCategory {
    PERSONAL,
    PROJECTS,
    HEALTH,
    CONVERSATIONS,
    RECOMMENDATIONS
}

enum class CertaintyLevel {
    CONFIRMED,
    EXPECTATION,
    INFERENCE
}

@Entity(tableName = "users")
data class UserProfileEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val heightCm: Float? = null,
    val weightKg: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memories",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val category: MemoryCategory,
    val certainty: CertaintyLevel,
    val title: String,
    val content: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val isProactive: Boolean = false,
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "health_scans",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class HealthScanEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val scanType: String, // "FACE", "EYE", "SKIN", "HAIR", "BODY", "BIOFINGER_PPG"
    val summary: String,
    val findingsJson: String,
    val potentialIssues: String,
    val severityLevel: String = "غير محدد",
    val limitations: String = "تقدير بصري غير تشخيصي يعتمد على جودة الإضاءة والصورة.",
    val recommendations: String,
    val doctorWarning: String,
    val confidenceLabel: String, // e.g. "0.78" or "غير كافٍ"
    val isNonDiagnosticEstimate: Boolean = true,
    val sourceDescription: String,
    val savedImagePath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "lab_readings",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class LabReadingEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val sectionId: String = "GLUCOSE", // 12 Health Center sections
    val testCode: String, // BP, GLUCOSE, HBA1C, CBC, THYROID, PULSE, RESPIRATORY_SPO2, BMI, BODY_COMPOSITION, LIVER_KIDNEY, FACE_EYE_SKIN, HAIR_SCALP
    val testNameAr: String,
    val measuredValue: String,
    val unit: String,
    val subMetricsJson: String = "{}",
    val source: String, // e.g. "جهاز قياس منزلي موثق" or "نتيجة مختبر معتمد"
    val explanationAr: String = "",
    val notes: String = "",
    val recordedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "uploaded_images",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class UploadedImageEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val sectionId: String, // FACE_EYE_SKIN | HAIR_SCALP | BODY_COMPOSITION
    val sourceOrigin: String, // camera | gallery | file
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val serverUrl: String,
    val status: String, // uploaded | analyzed | failed
    val analysisSummary: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "scan_sessions",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class ScanSessionEntity(
    @PrimaryKey val sessionId: String,
    val userId: String,
    val currentStage: String, // intro | face | body | hair | summary
    val stageResultsJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "health_reports",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class ReportRecordEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val reportType: String = "COMPREHENSIVE", // 12 section reports + COMPREHENSIVE
    val title: String,
    val pdfFilePath: String,
    val referenceImagePath: String? = null,
    val scanIdsIncluded: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "health_risk_assessments",
    foreignKeys = [ForeignKey(
        entity = UserProfileEntity::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class HealthRiskAssessmentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val category: String, // RISK_DIABETES, RISK_BP, RISK_THYROID_BLOOD, HEART, GENERAL_HEALTH
    val categoryTitleAr: String,
    val riskLevel: String, // منخفض | متوسط | مرتفع | يحتاج فحصًا طبيًا | بيانات غير كافية
    val ageYears: Int?,
    val genderAr: String,
    val bmiValue: Float?,
    val rationaleAr: String,
    val recommendedTestsAr: String,
    val suggestionsAr: String,
    val disclaimerAr: String = "تقييم خطورة غير تشخيصي — لا يُستبدل بالفحص المختبري أو استشارة الطبيب.",
    val timestamp: Long = System.currentTimeMillis()
)
