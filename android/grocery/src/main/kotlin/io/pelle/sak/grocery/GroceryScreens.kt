package io.pelle.sak.grocery

import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.api.model.GroceryListResponse
import io.pelle.sak.api.model.GrocerySuggestionResponse
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.UiText
import io.pelle.sak.core.update.AppUpdate

// what the screens of the app show once the user is signed in, as GroceryModel holds it

// the items of a category, in the order of the categories of the list, the ones without one last
// (GROCERY-13)
data class ItemGroup(
    // the id of the category, null for the items without one; the key of the group
    val categoryId: Int?,
    // the name of the category, or no category
    val label: UiText,
    // the emoji of the category, null without one
    val icon: String?,
    val items: List<GroceryItemResponse>,
)

// the check that can still be undone; serial starts the bar of the time left anew for every check
data class Undo(
    val text: UiText,
    val serial: Int,
)

// the item renamed in a dialog over the list (GROCERY-14), with the name typed; its error shows once
// the field was left or the name was to be saved (UX-6)
data class Renaming(
    val item: GroceryItemResponse,
    val name: String,
    val left: Boolean = false,
)

// the settings of a list the user creates (no id) or changes, one of their own: its name, whether
// all users see it and its icon, one emoji; of a list that exists also its categories, each change
// of them saved at once (GROCERY-13)
data class ListSettings(
    val id: Int? = null,
    val name: String = "",
    val shared: Boolean = false,
    val icon: String? = null,
    // a list of the user's own; a shared list of another user changes its name, icon and categories,
    // only its owner shares, unshares and deletes it (LIST-2)
    val mine: Boolean = true,
    val busy: Boolean = false,
    val error: UiText? = null,
    // the categories of the list in their order, none for a new list
    val categories: List<GroceryCategoryResponse> = emptyList(),
    // the categories are as the backend has them; false while they load the first time and when they
    // could not be loaded, the section then shows no rows and no field to add one
    val categoriesLoaded: Boolean = false,
    // while the categories load
    val categoriesLoading: Boolean = false,
    // the names typed into the fields of the categories, by their id, until they are saved; they
    // stay over a load and are saved before the settings close
    val drafts: Map<Int, String> = emptyMap(),
    // the categories whose typed name is no valid one, said under its field (UX-6)
    val invalidNames: Set<Int> = emptySet(),
    // the name typed into the field that adds a category, and whether it is no valid one
    val newCategory: String = "",
    val newCategoryInvalid: Boolean = false,
    // how many changes of the categories wait or are sent, one after the other
    val categoriesPending: Int = 0,
    // why the categories could not be loaded or a change of them was refused, said in their section
    val categoriesError: UiText? = null,
)

// the sizes the backend scales a photo to, on its longer side (FileService.SCALED_SIZES)
val SCALED_SIZES = listOf(128, 256, 480, 1024)

// the size to ask for of a photo that shows at pixels on the screen: the smallest scaled size that
// is at least as large, null when none is, the photo itself then
fun scaledSize(pixels: Int): Int? = SCALED_SIZES.firstOrNull { it >= pixels }

// the screen of the grocery lists once the user is signed in (the screens before are core's)
data class ListsScreen(
    // who is signed in: their name, else the e-mail address, or admin
    override val user: String,
    val lists: List<GroceryListResponse> = emptyList(),
    // the id of the list open, null on the home, which shows all lists
    val open: Int? = null,
    val groups: List<ItemGroup> = emptyList(),
    // the categories of the list open, in their order (GROCERY-13)
    val categories: List<GroceryCategoryResponse> = emptyList(),
    // the item whose category is chosen, on a screen over the list; null for the list
    val choosing: GroceryItemResponse? = null,
    // the item renamed in a dialog over the list, kept when its row goes, e.g. after a change event
    val renaming: Renaming? = null,
    // the item whose Delete asks for a confirmation in a dialog over the list (UX-4.1)
    val deleting: GroceryItemResponse? = null,
    val loading: Boolean = true,
    val error: UiText? = null,
    // the name typed into the field at the top, kept while it is sent (GROCERY-2)
    val itemName: String = "",
    val adding: Boolean = false,
    // the items checked while they can be undone, struck through until they go
    val struck: Set<Int> = emptySet(),
    // whether the list open shows its bought items too, struck through (GROCERY-14); hidden when a list opens
    val showBought: Boolean = false,
    val undo: Undo? = null,
    // the screen of the settings of a list, a new one or one of the user's own, over the home
    val settings: ListSettings? = null,
    // the newer version of the app the deployment runs, offered on the home
    override val update: AppUpdate? = null,
    // the answer of the check for updates of the menu while there is no update to offer, e.g. that
    // the app is up-to-date; on the home until the user dismisses it
    override val updateNotice: UiText? = null,
    // the item whose photo is being attached or removed
    val photoBusy: Int? = null,
    // the item whose photo shows in full, over the list
    val viewing: GroceryItemResponse? = null,
    // what the list suggests for the name typed (GROCERY-11), and the one chosen, whose quantity and
    // category the new item takes while its name stays
    val suggestions: List<GrocerySuggestionResponse> = emptyList(),
    val chosen: GrocerySuggestionResponse? = null,
) : AppScreen {
    override fun withUser(user: String) = copy(user = user)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)
}
