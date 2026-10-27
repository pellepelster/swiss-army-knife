package io.pelle.sak.backend

// a part of a list read from the database and how many items the whole list has
data class Slice<T>(
    val items: List<T>,
    val total: Long,
)
