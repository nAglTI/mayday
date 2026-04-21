package org.debs.kalpn.core.data.repository

import kotlinx.coroutines.flow.Flow
import org.debs.kalpn.core.model.VpnProfile

interface VpnProfileRepository {
    val profile: Flow<VpnProfile>

    suspend fun save(profile: VpnProfile)
}
