package org.debs.mayday.core.data.repository

import kotlinx.coroutines.flow.Flow
import org.debs.mayday.core.model.VpnProfile

interface VpnProfileRepository {
    val profile: Flow<VpnProfile>

    suspend fun save(profile: VpnProfile)
}
