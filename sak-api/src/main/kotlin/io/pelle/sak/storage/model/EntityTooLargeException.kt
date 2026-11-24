package io.pelle.sak.storage.model

// a content larger than its store takes
class EntityTooLargeException(
    maxBytes: Long,
) : RuntimeException("a content has at most $maxBytes bytes")
