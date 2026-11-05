package com.xencode.di

import com.xencode.feature.backup.data.BackupManagerImpl
import com.xencode.feature.backup.domain.BackupManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {

    @Binds
    @Singleton
    abstract fun bindBackupManager(impl: BackupManagerImpl): BackupManager
}
