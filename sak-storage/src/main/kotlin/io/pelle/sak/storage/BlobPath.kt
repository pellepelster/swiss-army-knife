package io.pelle.sak.s3.storage

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

        private val NUL: Char = Char(0)

        fun requireValidSegment(segment: String) =
            require(
                segment.isNotEmpty() &&
                    segment != "." &&
                    segment != ".." &&
                    !segment.contains('/') &&
                    !segment.contains(NUL),
            ) {
                "invalid storage path segment '$segment'"
            }
    }
}
