package com.wesal.messenger.core.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.wesal.messenger.data.local.WesalDatabase
import com.wesal.messenger.data.local.dao.WesalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppDiModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance(com.google.firebase.FirebaseApp.getInstance(), "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424")

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = FirebaseStorage.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging = FirebaseMessaging.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFunctions(): FirebaseFunctions = FirebaseFunctions.getInstance()

    @Provides
    @Singleton
    fun provideWesalDatabase(@ApplicationContext context: Context): WesalDatabase {
        return Room.databaseBuilder(
            context,
            WesalDatabase::class.java,
            "wesal_local_cache.db"
        ).addMigrations(WesalDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration().build()
    }

    @Provides
    @Singleton
    fun provideWesalDao(database: WesalDatabase): WesalDao = database.wesalDao()
}
