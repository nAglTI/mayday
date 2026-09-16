package org.debs.mayday.core.data.tunnel

import org.debs.mayday.core.model.TunnelAccessEvent

/** Limits repeated owner lookups, not packets. Worker confinement keeps callbacks lock-free. */
internal class TunnelAccessDeduplicator(
    private val windowNanos: Long = 30_000_000_000L,
    private val maxEntries: Int = 4096,
    private val elapsedNanos: () -> Long = System::nanoTime
) {
    private val lastObserved = LinkedHashMap<TunnelAccessEvent, Long>(16, 0.75f, true)

    init {
        require(windowNanos > 0)
        require(maxEntries > 0)
    }

    fun shouldRecord(event: TunnelAccessEvent): Boolean {
        val key = event.copy(
            timestampEpochMillis = 0,
            packages = event.packages.sorted(),
            matchedPackages = event.matchedPackages.sorted()
        )
        val now = elapsedNanos()
        val previous = lastObserved[key]
        if (previous != null && now - previous in 0 until windowNanos) return false
        lastObserved[key] = now
        if (lastObserved.size > maxEntries) {
            val iterator = lastObserved.entries.iterator()
            iterator.next()
            iterator.remove()
        }
        return true
    }

    fun clear() = lastObserved.clear()
}
