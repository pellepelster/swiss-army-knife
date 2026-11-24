package io.pelle.sak.api.storage.model

class BlobPath(
    val segments: List<String>,
) {
    init {
        segments.forEach { requireValidSegment(it) }
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

    // the rules of every store: no empty segment, none that leads elsewhere, no slash, no NUL. A store
    // may have more, e.g. the volume a longest name (FilesystemLocalStorage)
    companion object {
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
