package io.pelle.sak.backend.notes.model

import io.pelle.sak.backend.notes.NoteColor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// the paper color of a note as it is answered, by the id a NoteRequest names it with
@Serializable
enum class NoteColorResponse(
    val color: NoteColor,
) {
    @SerialName("yellow")
    YELLOW(NoteColor.YELLOW),

    @SerialName("pink")
    PINK(NoteColor.PINK),

    @SerialName("blue")
    BLUE(NoteColor.BLUE),

    @SerialName("green")
    GREEN(NoteColor.GREEN),

    @SerialName("orange")
    ORANGE(NoteColor.ORANGE),
    ;

    companion object {
        fun of(color: NoteColor) = entries.first { it.color == color }
    }
}
