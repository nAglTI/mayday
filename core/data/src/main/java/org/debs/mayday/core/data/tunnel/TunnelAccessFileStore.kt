package org.debs.mayday.core.data.tunnel

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import org.debs.mayday.core.model.TunnelAccessEvent

internal interface TunnelAccessStore {
    fun readLatest(limit: Int): List<TunnelAccessEvent>
    fun append(event: TunnelAccessEvent)
    fun clear()
    fun exportJsonLines(): String
}

/** Called exclusively by the journal worker, including construction and first directory access. */
internal class TunnelAccessFileStore(
    private val directory: File,
    private val maxFileBytes: Long = 1024L * 1024L
) : TunnelAccessStore {
    private val current = File(directory, "current.jsonl")
    private val previous = File(directory, "previous.jsonl")

    init {
        require(maxFileBytes > 0)
    }

    override fun readLatest(limit: Int): List<TunnelAccessEvent> {
        require(limit > 0)
        val events = ArrayDeque<TunnelAccessEvent>()
        forEachRetainedEvent { event ->
            if (events.size == limit) events.removeFirst()
            events.addLast(event)
        }
        return events.reversed()
    }

    override fun append(event: TunnelAccessEvent) {
        val bytes = (TunnelAccessEventCodec.encode(event) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFileBytes) { "Tunnel observation exceeds the journal file limit" }
        ensureDirectory()
        var needsNewline = needsTrailingNewline()
        if (current.length() + bytes.size + (if (needsNewline) 1 else 0) > maxFileBytes) {
            if (previous.exists() && !previous.delete()) {
                throw IOException("Cannot rotate the previous tunnel journal")
            }
            if (current.exists() && !current.renameTo(previous)) {
                throw IOException("Cannot rotate the current tunnel journal")
            }
            needsNewline = false
        }
        FileOutputStream(current, true).use { output ->
            // Preserve a complete unterminated line and isolate an interrupted write after restart.
            if (needsNewline) output.write('\n'.code)
            output.write(bytes)
        }
    }

    override fun clear() {
        for (file in listOf(previous, current)) {
            if (file.exists() && !file.delete()) throw IOException("Cannot clear the tunnel journal")
        }
    }

    override fun exportJsonLines(): String = buildString {
        forEachRetainedEvent { event ->
            append(TunnelAccessEventCodec.encode(event))
            append('\n')
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create the tunnel journal directory")
        }
    }

    private fun needsTrailingNewline(): Boolean {
        if (!current.exists() || current.length() == 0L) return false
        return RandomAccessFile(current, "r").use { file ->
            file.seek(file.length() - 1)
            file.read() != '\n'.code
        }
    }

    private inline fun forEachRetainedEvent(consume: (TunnelAccessEvent) -> Unit) {
        for (file in listOf(previous, current)) {
            if (!file.exists()) continue
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    TunnelAccessEventCodec.decode(line)?.let(consume)
                }
            }
        }
    }
}
