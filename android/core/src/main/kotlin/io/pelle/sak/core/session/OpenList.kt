package io.pelle.sak.core.session

// the value of a list-based app (grocery, todo) in the store of the session (Stored.app): the id of the
// list open when the app was left, opened again on the next start (LIST-3); none on the home. It goes
// with the session
private const val OPEN_LIST = "open_list"

val Stored.openList: Int? get() = app[OPEN_LIST]?.toIntOrNull()

fun Stored.withOpenList(id: Int?): Stored = copy(app = if (id == null) app - OPEN_LIST else app + (OPEN_LIST to id.toString()))
