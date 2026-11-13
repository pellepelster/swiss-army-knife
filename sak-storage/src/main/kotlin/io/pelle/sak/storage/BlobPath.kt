package io.pelle.sak.storage

class BlobPath(
    val segments: List<String>,
) {
    init {
        segments.forEach { requireValidSegment(it) }
        require(segments.firstOrNull() != STAGING_DIRECTORY) { "'$STAGING_DIRECTORY' is reserved for staged writes" }
    }

    fun resolve(vararg segments: String) = resolve(segments.toList())

    fun resolve(segments: List<String>) = BlobPath(this.segments + segments)

    override fun toString() = segments.joinToString("/")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobPath) return false
        return segments == other.segments
    }

    override fun hashCode() = segments.hashCode()

    companion object {
        const val STAGING_DIRECTORY = ".tmp"

        // the longest name of a file the file systems of the data volume take, in bytes of UTF-8
        // (SR-8): a longer segment is refused here, not when the blob is moved into place
        const val MAX_SEGMENT_BYTES = 255

        private val NUL: Char = Char(0)

        fun requireValidSegment(segment: String) =
            require(
                segment.isNotEmpty() &&
                    segment != "." &&
                    segment != ".." &&
                    !segment.contains('/') &&
                    !segment.contains(NUL) &&
                    segment.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES,
            ) {
                "invalid storage path segment '$segment'"
            }
    }
}
