package io.pelle.sak.grocery

import io.pelle.sak.core.session.Stored

// the value of the app in the store of the session (Stored.app): the id of the list open when the app
// was left, opened again on the next start; none on the home. It goes with the session
private const val OPEN_LIST = "open_list"

val Stored.openList: Int? get() = app[OPEN_LIST]?.toIntOrNull()

fun Stored.withOpenList(id: Int?): Stored = copy(app = if (id == null) app - OPEN_LIST else app + (OPEN_LIST to id.toString()))
