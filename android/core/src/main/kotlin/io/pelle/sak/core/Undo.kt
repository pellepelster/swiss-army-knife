package io.pelle.sak.core

// what was just done and can still be undone (UX-4.2), shown with its undo (UndoBar); serial starts the
// bar of the time left restarts for every one
data class Undo(
    val text: UiText,
    val serial: Int,
)
