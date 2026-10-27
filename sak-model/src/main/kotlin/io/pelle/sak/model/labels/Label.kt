package io.pelle.sak.model.labels

import java.time.Instant

// a label every application can attach to its entities; names are lower case and unique among
// the labels that are not deleted
data class Label(
    val id: Int,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
