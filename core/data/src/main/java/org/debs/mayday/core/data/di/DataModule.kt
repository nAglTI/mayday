package org.debs.mayday.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.debs.mayday.core.data.packageinfo.DefaultInstalledAppsRepository
import org.debs.mayday.core.data.packageinfo.InstalledAppsRepository
import org.debs.mayday.core.data.repository.DefaultUiPreferencesRepository
import org.debs.mayday.core.data.repository.DefaultVpnProfileRepository
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {

    @Binds
    @Singleton
    abstract fun bindVpnProfileRepository(
        repository: DefaultVpnProfileRepository,
    ): VpnProfileRepository

    @Binds
    @Singleton
    abstract fun bindInstalledAppsRepository(
        repository: DefaultInstalledAppsRepository,
    ): InstalledAppsRepository

    @Binds
    @Singleton
    abstract fun bindUiPreferencesRepository(
        repository: DefaultUiPreferencesRepository,
    ): UiPreferencesRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("mayday.preferences_pb")
        }
    }
}
