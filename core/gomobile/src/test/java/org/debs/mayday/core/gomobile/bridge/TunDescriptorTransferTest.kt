package org.debs.mayday.core.gomobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunDescriptorTransferTest {
    @Test fun earlyRunnerFailureClosesFrontendOwnedDescriptor() {
        val closed = mutableListOf<Int>()
        val result = transferTunDescriptor(19, closed::add) { error("Runner unavailable") }
        assertTrue(result.isFailure)
        assertEquals(listOf(19), closed)
    }

    @Test fun nativeFailureAfterAcceptanceDoesNotDoubleCloseReusedDescriptor() {
        val closed = mutableListOf<Int>()
        val result = transferTunDescriptor(19, closed::add) { accept ->
            accept()
            // Native has already closed fd, which Android could immediately reuse.
            error("Native validation or application failed")
        }
        assertTrue(result.isFailure)
        assertTrue(closed.isEmpty())
    }

    @Test fun successfulTransferLeavesClosingToCore() {
        val closed = mutableListOf<Int>()
        val result = transferTunDescriptor(19, closed::add) { it() }
        assertTrue(result.isSuccess)
        assertTrue(closed.isEmpty())
    }

    @Test fun invalidDescriptorNeverEntersNativeOrClosesAnUnrelatedDescriptor() {
        var entered = false
        val result = transferTunDescriptor(-1, { error("Invalid close") }) { entered = true }
        assertTrue(result.isFailure)
        assertEquals(false, entered)
    }
}
