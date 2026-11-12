package io.pelle.sak.backend.todo.model

import kotlinx.serialization.Serializable

// the done tasks of a list the user saw when they purged it, the only ones that go (TODO-6): one done
// meanwhile, by them or another user, stays
@Serializable
data class TodoPurgeRequest(
    val itemIds: List<Int>,
)
