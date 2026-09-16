package org.debs.mayday.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.debs.mayday.core.data.tunnel.AsyncTunnelAccessJournal
import org.debs.mayday.core.data.tunnel.DataStoreTunnelAccessLogSettings
import org.debs.mayday.core.data.tunnel.TunnelAccessFileStore

@Singleton
class DefaultTunnelAccessLogRepository @Inject constructor(
    @ApplicationContext context: Context,
    dataStore: DataStore<Preferences>
) : TunnelAccessLogRepository by AsyncTunnelAccessJournal(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    settings = DataStoreTunnelAccessLogSettings(dataStore),
    storeFactory = { TunnelAccessFileStore(File(context.noBackupFilesDir, "tunnel-access")) }
)
