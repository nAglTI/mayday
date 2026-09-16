package org.debs.mayday.core.vpn.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.gomobile.bridge.VpnCoreBridge
import org.debs.mayday.core.gomobile.bridge.VpnCoreConfigEncoder
import org.debs.mayday.core.model.VpnProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** In-process commands keep the profile (including keys) out of Android Intent extras. */
@Singleton
class VpnProfileUpdateCoordinator @Inject constructor(
    private val repository: VpnProfileRepository,
    private val bridge: VpnCoreBridge,
    private val encoder: VpnCoreConfigEncoder
) {
    val lifecycleMutex = Mutex()
    @Volatile private var owner: Any? = null
    @Volatile private var applyToService: (suspend (VpnProfile) -> Unit)? = null

    @Synchronized
    fun attach(owner: Any, apply: suspend (VpnProfile) -> Unit) {
        this.owner = owner
        applyToService = apply
    }

    @Synchronized
    fun detach(owner: Any) {
        if (this.owner === owner) {
            applyToService = null
            this.owner = null
        }
    }

    fun isOwner(owner: Any): Boolean = this.owner === owner

    suspend fun updateProfile(candidate: VpnProfile): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            coroutineContext.ensureActive()
            // A screen closing must not interrupt the native-commit -> persistence sequence.
            withContext(NonCancellable) {
                runCatching {
                    val apply = applyToService
                    if (apply != null) {
                        apply(candidate)
                    } else {
                        bridge.recommendedMtu(encoder.encode(candidate)).getOrThrow()
                        repository.save(candidate)
                    }
                }
            }
        }
    }
}
