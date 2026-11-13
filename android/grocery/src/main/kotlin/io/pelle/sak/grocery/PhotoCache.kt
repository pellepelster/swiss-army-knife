package io.pelle.sak.grocery

// the photos used last, at most maxBytes in all; a larger photo is not kept. Without android, so the
// model stays testable on the jvm
class PhotoCache(
    private val maxBytes: Long,
) {
    private val photos = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: String): ByteArray? = photos[key]

    @Synchronized
    fun put(
        key: String,
        photo: ByteArray,
    ) {
        photos.remove(key)?.let { bytes -= it.size }
        if (photo.size > maxBytes) return
        photos[key] = photo
        bytes += photo.size
        val eldest = photos.entries.iterator()
        while (bytes > maxBytes && eldest.hasNext()) {
            bytes -= eldest.next().value.size
            eldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        photos.clear()
        bytes = 0
    }

    // for the tests
    @Synchronized
    fun size(): Long = bytes
}
