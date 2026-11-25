package com.wesal.messenger.core.di

import com.wesal.messenger.domain.repository.*
import com.wesal.messenger.data.repository.AuthRepositoryImpl
import com.wesal.messenger.data.repository.CallRepositoryImpl
import com.wesal.messenger.data.repository.ChannelRepositoryImpl
import com.wesal.messenger.data.repository.ChatRepositoryImpl
import com.wesal.messenger.data.repository.GroupRepositoryImpl
import com.wesal.messenger.data.repository.StoryRepositoryImpl
import com.wesal.messenger.data.repository.UserRepositoryImpl
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.domain.repository.ChannelRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.GroupRepository
import com.wesal.messenger.domain.repository.StoryRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository

    @Binds
    @Singleton
    abstract fun bindChannelRepository(impl: ChannelRepositoryImpl): ChannelRepository

    @Binds
    @Singleton
    abstract fun bindStoryRepository(impl: StoryRepositoryImpl): StoryRepository

    @Binds
    @Singleton
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository
}
