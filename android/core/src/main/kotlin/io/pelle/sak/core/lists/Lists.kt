package io.pelle.sak.core.lists

import io.pelle.sak.core.UiText

// what the list-based apps (grocery, todo) share: the lists on the home and the settings of a list
// (components/INDEX.md)

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

// the fields of the settings of a list the user creates (no id) or changes: its name, whether all
// users see it and its icon, one emoji; busy while they are sent, error why they were refused. A shared
// list of another user changes its icon, only its owner renames, shares, unshares and deletes it (LIST-2)
data class ListFields(
    val id: Int? = null,
    val name: String = "",
    val shared: Boolean = false,
    val icon: String? = null,
    val mine: Boolean = true,
    val busy: Boolean = false,
    val error: UiText? = null,
) : ListForm<ListFields> {
    override val fields get() = this

    override fun withFields(fields: ListFields) = fields
}

// the settings of a list of an app: its fields (ListFields), and what the app adds, e.g. the categories
// of a grocery list
interface ListForm<S : ListForm<S>> {
    val fields: ListFields

    fun withFields(fields: ListFields): S
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

// an entry of the list open renamed in a dialog over the list, with the name typed; its error shows
// once the field was left or the name was to be saved (UX-6)
data class Renaming<I>(
    val item: I,
    val name: String,
    val left: Boolean = false,
) {
    // what Save does with the name typed: current is the name the entry has, valid its check
    fun submit(
        current: String,
        valid: (String) -> Boolean,
    ): Rename<I> {
        val trimmed = name.trim()
        return when {
            !valid(trimmed) -> Rename.Invalid(copy(left = true))
            trimmed == current -> Rename.Unchanged
            else -> Rename.Send(item, trimmed)
        }
    }
}

// the answer of Renaming.submit: invalid stays in the dialog with its error, unchanged closes it
// without a request, send closes it and sends the name trimmed
sealed interface Rename<out I> {
    data class Invalid<I>(val renaming: Renaming<I>) : Rename<I>

    data object Unchanged : Rename<Nothing>

    data class Send<I>(val item: I, val name: String) : Rename<I>
}
