package com.sabq.ai.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SabqDao {
    @Query("SELECT * FROM users ORDER BY createdAt ASC")
    fun observeUsers(): Flow<List<UserProfileEntity>>

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun getUser(userId: String): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserProfileEntity)

    @Query("DELETE FROM users WHERE userId = :userId")
    suspend fun deleteUser(userId: String)

    // Strict per-user memory isolation
    @Query("SELECT * FROM memories WHERE userId = :userId ORDER BY updatedAt DESC")
    fun observeMemories(userId: String): Flow<List<MemoryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(item: MemoryItemEntity)

    @Query("DELETE FROM memories WHERE id = :id AND userId = :userId")
    suspend fun deleteMemory(id: String, userId: String)

    // Strict per-user chat history
    @Query("SELECT * FROM chat_messages WHERE userId = :userId ORDER BY timestamp ASC")
    fun observeChats(userId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE userId = :userId")
    suspend fun clearUserChats(userId: String)

    // Strict per-user scans
    @Query("SELECT * FROM health_scans WHERE userId = :userId ORDER BY timestamp DESC")
    fun observeScans(userId: String): Flow<List<HealthScanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: HealthScanEntity)

    // Strict per-user verified lab readings
    @Query("SELECT * FROM lab_readings WHERE userId = :userId ORDER BY recordedAt DESC")
    fun observeLabReadings(userId: String): Flow<List<LabReadingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabReading(reading: LabReadingEntity)

    @Query("DELETE FROM lab_readings WHERE id = :id AND userId = :userId")
    suspend fun deleteLabReading(id: String, userId: String)

    // Strict per-user PDF reports
    @Query("SELECT * FROM health_reports WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeReports(userId: String): Flow<List<ReportRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: ReportRecordEntity)

    @Query("DELETE FROM health_reports WHERE id = :id AND userId = :userId")
    suspend fun deleteReport(id: String, userId: String)

    // Strict per-user risk assessments
    @Query("SELECT * FROM health_risk_assessments WHERE userId = :userId ORDER BY timestamp DESC")
    fun observeRiskAssessments(userId: String): Flow<List<HealthRiskAssessmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiskAssessment(item: HealthRiskAssessmentEntity)

    // Strict per-user uploaded images
    @Query("SELECT * FROM uploaded_images WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeUploadedImages(userId: String): Flow<List<UploadedImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUploadedImage(image: UploadedImageEntity)

    @Query("DELETE FROM uploaded_images WHERE id = :id AND userId = :userId")
    suspend fun deleteUploadedImage(id: String, userId: String)

    // Strict per-user scan session draft recovery
    @Query("SELECT * FROM scan_sessions WHERE userId = :userId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestScanSession(userId: String): ScanSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScanSession(session: ScanSessionEntity)

    @Query("DELETE FROM scan_sessions WHERE userId = :userId")
    suspend fun clearScanSessions(userId: String)
}
