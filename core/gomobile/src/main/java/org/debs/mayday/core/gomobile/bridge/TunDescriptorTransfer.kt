package org.debs.mayday.core.gomobile.bridge

/** A valid fd is owned by exactly one side, including exceptional native exits. */
internal fun transferTunDescriptor(
    fd: Int,
    closeUnaccepted: (Int) -> Unit,
    operation: (accept: () -> Unit) -> Unit
): Result<Unit> {
    var accepted = false
    return runCatching {
        require(fd >= 0) { "TUN descriptor must be nonnegative." }
        operation { accepted = true }
    }.also {
        if (!accepted && fd >= 0) runCatching { closeUnaccepted(fd) }
    }
}
