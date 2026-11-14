package com.sabq.ai.ai

import com.sabq.ai.data.local.CertaintyLevel
import com.sabq.ai.data.local.HealthScanEntity
import com.sabq.ai.data.local.MemoryItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class ProactiveInsight(
    val title: String,
    val body: String,
    val certainty: CertaintyLevel,
    val basisDescription: String
)

class SabqSecondBrainEngine(
    private val apiBaseUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun isQuietHours(startHour: Int, endHour: Int): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            hour in startHour until endHour
        } else {
            hour >= startHour || hour < endHour
        }
    }

    fun buildProactiveInsights(
        memories: List<MemoryItemEntity>,
        recentScans: List<HealthScanEntity>,
        quietStart: Int,
        quietEnd: Int
    ): List<ProactiveInsight> {
        if (isQuietHours(quietStart, quietEnd)) {
            return emptyList()
        }

        val insights = mutableListOf<ProactiveInsight>()
        val projectMemories = memories.filter { it.category.name == "PROJECTS" }
        val healthMemories = memories.filter { it.category.name == "HEALTH" }

        if (projectMemories.isNotEmpty()) {
            val latestProject = projectMemories.first()
            insights.add(
                ProactiveInsight(
                    title = "متابعة مشروع محفوظ: ${latestProject.title}",
                    body = "بناءً على ما طلبت حفظه في ذاكرة المشروعات، هل ترغب في تحديد الخطوة التنفيذية التالية لـ (${latestProject.title})؟",
                    certainty = CertaintyLevel.INFERENCE,
                    basisDescription = "مبني على ذاكرة المشروعات المحفوظة بإذنك"
                )
            )
        }

        if (recentScans.size >= 2) {
            val latest = recentScans.first()
            insights.add(
                ProactiveInsight(
                    title = "مقارنة التغيرات الظاهرية",
                    body = "لديك ${recentScans.size} فحوص محفوظة. آخر فحص (${latest.scanType}) متاح للمقارنة داخل قسم التقارير.",
                    certainty = CertaintyLevel.CONFIRMED,
                    basisDescription = "سجل الفحوص الفعلي المحفوظ"
                )
            )
        }

        if (healthMemories.isNotEmpty()) {
            val note = healthMemories.first()
            insights.add(
                ProactiveInsight(
                    title = "تذكير بمعلومة صحية حفظتها",
                    body = note.content,
                    certainty = note.certainty,
                    basisDescription = "المصدر: ${note.source}"
                )
            )
        }

        return insights
    }

    suspend fun sendChatPrompt(
        userId: String,
        message: String,
        memories: List<MemoryItemEntity>,
        imageBase64: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("userId", userId)
                put("message", message)
                put("memories", JSONArray().apply {
                    memories.take(12).forEach { m ->
                        put(JSONObject().apply {
                            put("category", m.category.name)
                            put("certainty", m.certainty.name)
                            put("title", m.title)
                            put("content", m.content)
                        })
                    }
                })
                if (imageBase64 != null) put("imageBase64", imageBase64)
            }

            val req = Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/api/ai/chat")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ في الاتصال بالخادم (${resp.code})")
                }
                val body = resp.body?.string() ?: "{}"
                JSONObject(body).optString("reply", "تعذر الحصول على رد حاليًا.")
            }
        }
    }
}
