package org.debs.mayday.core.vpn.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.gomobile.bridge.VpnCoreBridge
import org.debs.mayday.core.gomobile.bridge.VpnCoreConfigEncoder
import org.debs.mayday.core.gomobile.bridge.VpnCoreLaunchRequest
import org.debs.mayday.core.gomobile.bridge.VpnCoreUpdateRequest
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfileUpdateCoordinatorTest {
    @Test fun offlineValidationRunsBeforePersistence() = runBlocking {
        val events = mutableListOf<String>()
        val repository = FakeRepository(events)
        val coordinator = VpnProfileUpdateCoordinator(repository, FakeBridge(events), VpnCoreConfigEncoder())
        assertTrue(coordinator.updateProfile(profile()).isSuccess)
        assertEquals(listOf("validate", "persist"), events)
        assertEquals(profile(), repository.profile.value)
    }

    @Test fun nativeValidationFailureLeavesSavedProfileIntact() = runBlocking {
        val events = mutableListOf<String>()
        val repository = FakeRepository(events)
        val original = repository.profile.value
        val bridge = FakeBridge(events).apply { reject = true }
        val coordinator = VpnProfileUpdateCoordinator(repository, bridge, VpnCoreConfigEncoder())
        assertTrue(coordinator.updateProfile(profile()).isFailure)
        assertEquals(listOf("validate"), events)
        assertEquals(original, repository.profile.value)
    }

    @Test fun failedLiveUpdateDoesNotFallBackToSavingTheRejectedCandidate() = runBlocking {
        val events = mutableListOf<String>()
        val repository = FakeRepository(events)
        val coordinator = VpnProfileUpdateCoordinator(repository, FakeBridge(events), VpnCoreConfigEncoder())
        val owner = Any()
        coordinator.attach(owner) {
            events += "native-update"
            error("TUN replacement failed")
        }
        assertTrue(coordinator.updateProfile(profile()).isFailure)
        assertEquals(listOf("native-update"), events)
        assertEquals(VpnProfile(), repository.profile.value)
    }

    @Test fun closingScreenAfterNativeCommitDoesNotCancelPersistence() = runBlocking {
        val events = mutableListOf<String>()
        val repository = FakeRepository(events)
        val coordinator = VpnProfileUpdateCoordinator(repository, FakeBridge(events), VpnCoreConfigEncoder())
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coordinator.attach(Any()) { candidate ->
            events += "native-commit"
            committed.complete(Unit)
            release.await()
            repository.save(candidate)
        }
        val saving = launch { coordinator.updateProfile(profile()) }
        committed.await()
        saving.cancel()
        release.complete(Unit)
        saving.join()
        assertEquals(listOf("native-commit", "persist"), events)
        assertEquals(profile(), repository.profile.value)
    }

    @Test fun destroyedServiceCannotDetachItsReplacement() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = VpnProfileUpdateCoordinator(FakeRepository(events), FakeBridge(events), VpnCoreConfigEncoder())
        val old = Any()
        val current = Any()
        coordinator.attach(old) { error("Old service must not run") }
        coordinator.attach(current) { events += "current-service" }
        coordinator.detach(old)
        assertTrue(coordinator.updateProfile(profile()).isSuccess)
        assertEquals(listOf("current-service"), events)
    }

    private class FakeRepository(private val events: MutableList<String>) : VpnProfileRepository {
        override val profile = MutableStateFlow(VpnProfile())
        override suspend fun save(profile: VpnProfile) {
            events += "persist"
            this.profile.value = profile
        }
    }

    private class FakeBridge(private val events: MutableList<String>) : VpnCoreBridge {
        var reject = false
        override val isLinked = true
        override val linkErrorMessage: String? = null
        override val coreVersion = "2.1.2"
        override fun recommendedMtu(configJson: String): Result<Int> {
            events += "validate"
            return if (reject) Result.failure(IllegalArgumentException("Invalid candidate")) else Result.success(1280)
        }
        override suspend fun start(request: VpnCoreLaunchRequest) = Result.success(Unit)
        override fun supportedTransportsJson() = Result.success("[]")
        override fun statusJson() = Result.success("{}")
        override fun onPackageChanged(packageName: String) = Unit
        override fun onNetworkChange() = Unit
        override fun swapTun(tunFileDescriptor: Int) = Result.success(Unit)
        override fun configUpdateNeedsTun(configJson: String) = Result.success(false)
        override fun updateConfig(configJson: String) = Result.success(Unit)
        override fun updateSettings(settingsJson: String) = Result.success(Unit)
        override fun updateConfigAndTun(request: VpnCoreUpdateRequest) = Result.success(Unit)
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private fun profile() = VpnProfile(
        userId = "42",
        relays = listOf(VpnRelayTarget(
            id = "relay-test",
            addr = "relay.example.net",
            shortId = 1,
            relayKey = "a".repeat(64),
            transportPorts = mapOf("bt-utp" to listOf(52021))
        )),
        servers = listOf(VpnServerTarget("exit-test", "b".repeat(64), 1))
    )
}
