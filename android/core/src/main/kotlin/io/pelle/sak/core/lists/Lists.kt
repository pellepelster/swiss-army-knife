package io.pelle.sak.core.lists

import io.pelle.sak.core.UiText

// what the list-based apps (grocery, todo) share: the lists on the home and the settings of a list
// (apps/INDEX.md)

// a list on the home, as the app has it from the backend: its name, its emoji or none, whether all
// users see it, whether it is the user's own (LIST-2) and how many of its entries are open
data class ListSummary(
    val id: Int,
    val name: String,
    val icon: String?,
    val shared: Boolean,
    val mine: Boolean,
    val count: Int,
)

// the settings of a list the user creates (no id) or changes: its name, whether all users see it and
// its icon, one emoji; busy while they are sent, error why they were refused. A shared list of another
// user changes its name and icon, only its owner shares, unshares and deletes it (LIST-2)
interface ListForm {
    val id: Int?
    val name: String
    val shared: Boolean
    val icon: String?
    val mine: Boolean
    val busy: Boolean
    val error: UiText?
}

// what the screens of the lists ask of the model of a list-based app
interface ListsActions {
    // a list of the home opens (LIST-3)
    fun open(id: Int)

    // from the settings back to where they were opened, from a list to the home
    fun back()

    fun refresh()

    // the settings of a new list, from the home
    fun newList()

    // the settings of a list the user sees
    fun openSettings(id: Int)

    // a list of the user's own from the menu of its row on the home, after the user confirmed it
    fun deleteList(id: Int)

    fun editSettings(
        name: String,
        shared: Boolean,
        icon: String?,
    )

    fun saveSettings()

    // the list of the settings, after the user confirmed it
    fun deleteList()
}
