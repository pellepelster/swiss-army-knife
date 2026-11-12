package io.pelle.sak.backend.todo.model

import io.pelle.sak.model.todo.TodoPriority
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// how urgent a task is as it is sent (TODO-5)
@Serializable
enum class TodoPriorityValue(
    val priority: TodoPriority,
) {
    @SerialName("high")
    HIGH(TodoPriority.HIGH),

    @SerialName("normal")
    NORMAL(TodoPriority.NORMAL),

    @SerialName("low")
    LOW(TodoPriority.LOW),
    ;

    companion object {
        fun of(priority: TodoPriority) = entries.first { it.priority == priority }
    }
}
