package io.pelle.sak.core

// the contents used last (photos, files), at most maxBytes in all; a larger one is not kept. Without
// android, so the models stay testable on the jvm
class ByteCache(
    private val maxBytes: Long,
) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    operator fun get(key: String): ByteArray? = entries[key]

    @Synchronized
    fun put(
        key: String,
        content: ByteArray,
    ) {
        entries.remove(key)?.let { bytes -= it.size }
        if (content.size > maxBytes) return
        entries[key] = content
        bytes += content.size
        val eldest = entries.entries.iterator()
        while (bytes > maxBytes && eldest.hasNext()) {
            bytes -= eldest.next().value.size
            eldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    // for the tests
    @Synchronized
    fun size(): Long = bytes
}
