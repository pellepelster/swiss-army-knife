package io.pelle.sak.api.storage

import io.pelle.sak.api.storage.model.BlobListEntry

// what a walk in the order of the keys meets: a blob, or a directory before the blobs below it. The
// path is relative to the root of the walk; the key of a directory is its path and a slash
sealed interface KeyOrderEntry {
    val segments: List<String>

    data class Blob(
        val entry: BlobListEntry,
    ) : KeyOrderEntry {
        override val segments get() = entry.segments
    }

    data class Directory(
        override val segments: List<String>,
    ) : KeyOrderEntry {
        val key get() = segments.joinToString("/") + "/"
    }
}

// what the walk does after an entry: go on (into a directory too), go on without going into the
// directory, or end
enum class Walk { CONTINUE, SKIP, STOP }
