package io.pelle.sak.grocery

import androidx.annotation.StringRes
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.Screen
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.AppUpdate
import io.pelle.sak.core.update.Updater
import io.pelle.sak.grocery.api.GroceryApi
import io.pelle.sak.grocery.api.GroceryCategoryResponse
import io.pelle.sak.grocery.api.GroceryItemRequest
import io.pelle.sak.grocery.api.GroceryItemResponse
import io.pelle.sak.grocery.api.GroceryListResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

// how long a check can be undone, the same as in the web app (GROCERY_UNDO_MS): while shopping one
// item follows the other
const val UNDO_MS = 1500L

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

// the settings of a list the user creates (no id) or changes, one of their own: its name, whether
// all users see it and its icon, one emoji; of a list that exists also its categories, each change
// of them saved at once (GROCERY-13)
// the item renamed in a dialog over the list (GROCERY-14), with the name typed; its error shows once
// the field was left or the name was to be saved (UX-6)
data class Renaming(
    val item: GroceryItemResponse,
    val name: String,
    val left: Boolean = false,
)

data class ListSettings(
    val id: Int? = null,
    val name: String = "",
    val shared: Boolean = false,
    val icon: String? = null,
    // the only list of the user's own, which can not be deleted (LIST-1)
    val last: Boolean = false,
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
        override val deployment: String,
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
    ) : AppScreen {
    override fun withUser(user: String) = copy(user = user)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)
}

// the logic of the app, without android: the deployment, the sign-in by link or password, the lists
// on the home and the items to buy of the list open, an item added from the field at the top, a checked item deleted once
// its undo ran out. apiOf makes the api of a url with a session token or none
class GroceryModel(
    store: SessionStore,
    apiOf: (url: String, token: String?) -> GroceryApi,
    scope: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    allowUrl: Boolean,
    clock: Clock = Clock.systemUTC(),
    // the version of the app, 1.4.2 of a release, latest of every other build
    appVersion: String = "latest",
    updater: Updater = Updater { error("no updater") },
) : SakModel<GroceryApi>(store, apiOf, scope, allowUrl, clock, appVersion, updater) {
    private var loading: Job? = null

    // the stream of the changes while the app is in the foreground (LIST-4); whether it was open
    // before, a new one may have missed changes
    private var listening: Job? = null
    private var listenedBefore = false

    // the check that can still be undone: its item and the timer that sends it to the backend
    private var pending: Pair<GroceryItemResponse, Job>? = null
    private var serial = 0

    // the changes of the categories of the settings and their loads, one after the other in the order
    // they were made; a change takes the state it finds when it is its turn
    private val categoryChanges = Mutex()

    // the photos fetched, by their file and size, the ones used last kept; a file id names one photo
    private val photos =
        object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_PHOTOS
        }

    // the app comes to the foreground: it hears the changes of the lists once the user is signed in,
    // a change of the list open, or of any list on the home, loads it again (LIST-4). A stream that
    // ends is opened again, after a longer wait each time, up to MAX_RETRY_DELAY_MS
    fun startListening() {
        if (listening?.isActive == true) return
        listening =
            scope.launch {
                var wait = RETRY_DELAY_MS
                while (isActive) {
                    state.first { it is ListsScreen }
                    val stored = store.load()
                    val url = stored.apiUrl ?: return@launch
                    try {
                        apiOf(url, stored.token)
                            .changes(opened = {
                                wait = RETRY_DELAY_MS
                                // the changes meanwhile, while the app was away or the stream lost
                                if (listenedBefore) load()
                                listenedBefore = true
                            }).collect { listId ->
                                val lists = state.value as? ListsScreen
                                if (lists != null && (lists.open == null || lists.open == listId)) load()
                                // the categories of the settings of the list as the backend has them now
                                if (lists?.settings?.id == listId) loadCategories(listId)
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.status == 401) sessionEnded(e)
                    } catch (_: Exception) {
                        // the connection ended, it is opened again
                    }
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
    }

    // the app goes to the background, the stream is closed
    fun stopListening() {
        listening?.cancel()
        listening = null
    }

    // the session or the deployment changed: a stream that is open goes, with the token it was opened
    // with, and opens again for what is stored now
    private fun restartListening() {
        val wasListening = listening != null
        stopListening()
        listenedBefore = false
        if (wasListening) startListening()
    }

    // the item is bought and struck through, after UNDO_MS the backend learns it and it goes from the
    // list, unless it is undone; checked again while it can be undone, it is not bought after all. A
    // check before goes to the backend at once (GROCERY-3)
    fun check(item: GroceryItemResponse) {
        val lists = state.value as? ListsScreen ?: return
        // a bought item is checked already
        if (item.checkedAt != null) return
        if (item.id in lists.struck) {
            undo()
            return
        }
        commitPending()
        val timer =
            scope.launch {
                delay(UNDO_MS)
                commitPending()
            }
        pending = item to timer
        (state.value as? ListsScreen)?.let {
            state.value = it.copy(struck = it.struck + item.id, undo = Undo(uiText(R.string.grocery_bought, item.name), ++serial), error = null)
        }
    }

    fun undo() {
        val (item, timer) = pending ?: return
        timer.cancel()
        pending = null
        (state.value as? ListsScreen)?.let { state.value = it.copy(struck = it.struck - item.id, undo = null) }
    }

    // the check that can be undone goes to the backend now; a failure brings the list back as the
    // backend has it. The job of the request, null without a check
    private fun commitPending(): Job? {
        val (item, timer) = pending ?: return null
        pending = null
        timer.cancel()
        (state.value as? ListsScreen)?.let { lists ->
            val groups =
                if (lists.showBought) {
                    // it stays, as the latest bought item of its group, after the ones to buy
                    val bought = item.copy(checkedAt = clock.instant().toString())
                    lists.groups.map { group ->
                        if (group.items.none { it.id == item.id }) return@map group
                        val others = group.items.filter { it.id != item.id }
                        group.copy(items = others.filter { it.checkedAt == null } + bought + others.filter { it.checkedAt != null })
                    }
                } else {
                    lists.groups.map { it.copy(items = it.items.filter { each -> each.id != item.id }) }.filter { it.items.isNotEmpty() }
                }
            state.value = lists.copy(groups = groups, struck = lists.struck - item.id, undo = null)
        }
        return scope.launch {
            request(R.string.grocery_list_not_checked, ::failed) { api ->
                // bought already, e.g. by another device in the undo window, or deleted: done, no error
                try {
                    api.checkItem(item.id)
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
            }
        }
    }

    // the check that can be undone goes without being sent, e.g. the session ended
    private fun dropPending() {
        pending?.second?.cancel()
        pending = null
    }

    private fun failed(error: UiText) {
        val lists = state.value as? ListsScreen ?: return
        state.value = lists.copy(error = error)
        load()
    }

    // the Delete of the menu of an item asks for a confirmation first (UX-12, UX-4.1)
    fun askDelete(item: GroceryItemResponse) = lists { it.copy(deleting = item) }

    fun dismissDelete() = lists { it.copy(deleting = null) }

    // an item from the menu of its row, after the user confirmed it (UX-12): it goes for good, no
    // suggestion comes from it; a check of it that can still be undone goes without being sent. A
    // failure shows on the list, which loads as the backend has it
    fun deleteItem(item: GroceryItemResponse) {
        if (state.value !is ListsScreen) return
        if (pending?.first?.id == item.id) {
            dropPending()
            lists { it.copy(struck = it.struck - item.id, undo = null) }
        }
        lists { it.copy(deleting = null) }
        scope.launch {
            request(R.string.grocery_item_not_deleted, ::failed) { it.deleteItem(item.id) } ?: return@launch
            load()
        }
    }

    // the Rename of the menu of an item opens its dialog with the name as it is
    fun askRename(item: GroceryItemResponse) = lists { it.copy(renaming = Renaming(item, item.name)) }

    fun editRename(name: String) = lists { lists -> lists.copy(renaming = lists.renaming?.copy(name = name)) }

    // the field of the name was left: its error shows from now on (UX-6)
    fun leaveRename() = lists { lists -> lists.copy(renaming = lists.renaming?.copy(left = true)) }

    fun dismissRename() = lists { it.copy(renaming = null) }

    // the item of the dialog, to buy or bought, under the name typed, e.g. with a spelling error
    // fixed; its quantity and category stay. An invalid name stays in the dialog with its error, an
    // unchanged one is not sent (validItemName)
    fun renameItem() {
        val lists = state.value as? ListsScreen ?: return
        val renaming = lists.renaming ?: return
        val trimmed = renaming.name.trim()
        if (!validItemName(trimmed)) {
            state.value = lists.copy(renaming = renaming.copy(left = true))
            return
        }
        state.value = lists.copy(renaming = null)
        val item = renaming.item
        if (trimmed == item.name) return
        // a check that can still be undone puts the item back under its new name
        pending?.let { (waiting, timer) -> if (waiting.id == item.id) pending = waiting.copy(name = trimmed) to timer }
        scope.launch {
            request(R.string.grocery_item_not_renamed, ::failed) {
                it.updateItem(item.id, GroceryItemRequest(trimmed, item.amount, item.unit, item.categoryId))
            } ?: return@launch
            load()
        }
    }

    // a list of the home opens, the next start opens it again (LIST-3)
    fun open(id: Int) {
        val lists = state.value as? ListsScreen ?: return
        if (lists.open != null || lists.lists.none { it.id == id }) return
        show(id)
    }

    // from the settings or the category of an item back to where they were opened, from a list to the
    // home; the next start shows the home
    fun back() {
        val lists = state.value as? ListsScreen ?: return
        when {
            lists.renaming != null -> state.value = lists.copy(renaming = null)
            lists.deleting != null -> state.value = lists.copy(deleting = null)
            lists.settings != null -> {
                saveDrafts()
                this.lists { it.copy(settings = null) }
            }
            lists.choosing != null -> state.value = lists.copy(choosing = null)
            lists.open != null -> show(null)
        }
    }

    // the screen that chooses the category of an item from the categories of its list, from its menu
    fun chooseCategory(item: GroceryItemResponse) = lists { it.copy(choosing = item) }

    // the category chosen, one of the list or none: the list shows again at once, then with the item
    // in its group; a failure shows on the list
    fun setCategory(categoryId: Int?) {
        val lists = state.value as? ListsScreen ?: return
        val item = lists.choosing ?: return
        state.value = lists.copy(choosing = null, error = null)
        if (item.categoryId == categoryId) return
        scope.launch {
            request(R.string.grocery_category_not_set, { error -> lists { it.copy(error = error) } }) { it.setCategory(item.id, categoryId) }
                ?: return@launch
            load()
        }
    }

    // the settings of a new list, from the home
    fun newList() {
        val lists = state.value as? ListsScreen ?: return
        if (lists.open == null) state.value = lists.copy(settings = ListSettings())
    }

    // the settings of a list of the user's own, from its menu on the home or from the list itself;
    // back and save return there, a deleted list leads to the home
    fun openSettings(id: Int) {
        val lists = state.value as? ListsScreen ?: return
        val list = lists.lists.firstOrNull { it.id == id && it.mine } ?: return
        val last = lists.lists.count { it.mine } == 1
        state.value = lists.copy(settings = ListSettings(list.id, list.name, list.shared, list.icon, last))
        loadCategories(id)
    }

    // the categories of the settings loaded again, pulled down (ANDROID-3) or after a failed load
    fun refreshCategories() {
        val id = (state.value as? ListsScreen)?.settings?.id ?: return
        settings(id) { it.copy(categoriesError = null) }
        loadCategories(id)
    }

    // the categories of the list of the settings, as the backend has them, after the changes made
    // before; the names typed stay. A failure shows no categories, but why (GC-6)
    private fun loadCategories(id: Int) {
        settings(id) { it.copy(categoriesLoading = true) }
        scope.launch {
            categoryChanges.withLock {
                val failed = { error: UiText -> settings(id) { it.copy(categoriesLoaded = false, categoriesLoading = false, categoriesError = error) } }
                val categories = request(R.string.grocery_error_categories_not_loaded, failed) { it.categories(id) } ?: return@withLock
                settings(id) { settings ->
                    val ids = categories.map { it.id }.toSet()
                    settings.copy(
                        categories = categories,
                        categoriesLoaded = true,
                        categoriesLoading = false,
                        drafts = settings.drafts.filterKeys { it in ids },
                        invalidNames = settings.invalidNames.filter { it in ids }.toSet(),
                    )
                }
            }
        }
    }

    fun editNewCategory(name: String) = settingsOf { it.copy(newCategory = name, newCategoryInvalid = false) }

    // the name typed becomes the last category of the list, the field is empty again; a name that is
    // empty or too long is said under the field (UX-6)
    fun addCategory() {
        val settings = (state.value as? ListsScreen)?.settings ?: return
        val name = settings.newCategory.trim()
        if (!validCategoryName(name)) {
            settingsOf { it.copy(newCategoryInvalid = true) }
            return
        }
        categoriesRequest(R.string.grocery_category_not_saved) { api, id ->
            val created = api.createCategory(id, name, null)
            settings(id) { it.copy(categories = it.categories + created, newCategory = if (it.newCategory.trim() == name) "" else it.newCategory) }
        }
    }

    // the name typed into the field of a category, kept until it is saved
    fun editCategoryName(
        categoryId: Int,
        name: String,
    ) = settingsOf { it.copy(drafts = it.drafts + (categoryId to name), invalidNames = it.invalidNames - categoryId) }

    // the name typed into the field of a category, when the field is left or done: an empty or too
    // long one is said under the field (UX-6), the one the category has is no change
    fun saveCategoryName(categoryId: Int) {
        val settings = (state.value as? ListsScreen)?.settings ?: return
        val draft = settings.drafts[categoryId] ?: return
        val category = settings.categories.firstOrNull { it.id == categoryId } ?: return
        val name = draft.trim()
        when {
            !validCategoryName(name) -> settingsOf { it.copy(invalidNames = it.invalidNames + categoryId) }
            name == category.name -> settingsOf { it.copy(drafts = it.drafts - categoryId) }
            else -> rename(categoryId, draft, name)
        }
    }

    private fun rename(
        categoryId: Int,
        draft: String,
        name: String,
    ) = categoriesRequest(R.string.grocery_category_not_saved) { api, id ->
        val icon = currentCategory(id, categoryId)?.icon
        val updated = api.updateCategory(id, categoryId, name, icon)
        settings(id) { settings ->
            settings.copy(
                categories = settings.categories.map { if (it.id == categoryId) updated else it },
                // a name typed again meanwhile stays
                drafts = if (settings.drafts[categoryId] == draft) settings.drafts - categoryId else settings.drafts,
            )
        }
    }

    // the names typed and not yet saved, saved before the settings close (GC-5); an invalid one is not
    private fun saveDrafts() {
        val settings = (state.value as? ListsScreen)?.settings ?: return
        settings.drafts.forEach { (categoryId, draft) ->
            val name = draft.trim()
            val category = settings.categories.firstOrNull { it.id == categoryId }
            if (category != null && validCategoryName(name) && name != category.name) rename(categoryId, draft, name)
        }
    }

    // the icon of a category, one emoji or none; its name stays as the backend has it
    fun setCategoryIcon(
        categoryId: Int,
        icon: String?,
    ) = categoriesRequest(R.string.grocery_category_not_saved) { api, id ->
        val category = currentCategory(id, categoryId) ?: return@categoriesRequest
        if (category.icon == icon) return@categoriesRequest
        val updated = api.updateCategory(id, categoryId, category.name, icon)
        settings(id) { it.copy(categories = it.categories.map { each -> if (each.id == categoryId) updated else each }) }
    }

    // a category one place up or down, from the menu of its row or an action of a screen reader; the
    // ends stay
    fun moveCategory(
        categoryId: Int,
        up: Boolean,
    ) {
        val categories = (state.value as? ListsScreen)?.settings?.categories ?: return
        val from = categories.indexOfFirst { it.id == categoryId }
        moveCategory(categoryId, if (up) from - 1 else from + 1)
    }

    // a category dragged to the place to (GROCERY-13): the settings show the new order at once, the
    // order of all categories goes after the changes made before it (GC-4); a refused one loads them
    // again. Nothing for a place out of the list or the one it has
    fun moveCategory(
        categoryId: Int,
        to: Int,
    ) {
        val id = (state.value as? ListsScreen)?.settings?.id ?: return
        val categories = (state.value as? ListsScreen)?.settings?.categories ?: return
        val from = categories.indexOfFirst { it.id == categoryId }
        if (from < 0 || to !in categories.indices || to == from) return
        settings(id) { it.copy(categories = it.categories.toMutableList().apply { add(to, removeAt(from)) }) }
        categoriesRequest(R.string.grocery_category_not_saved) { api, listId ->
            // the order as it is when its turn comes, later moves are in it
            val ids = (state.value as? ListsScreen)?.settings?.takeIf { it.id == listId }?.categories?.map { it.id } ?: return@categoriesRequest
            api.orderCategories(listId, ids)
        }
    }

    // a category of the list of the settings, after the user confirmed it (UX-4.1); its items lose it
    fun deleteCategory(categoryId: Int) =
        categoriesRequest(R.string.grocery_category_not_deleted) { api, id ->
            api.deleteCategory(id, categoryId)
            settings(id) {
                it.copy(
                    categories = it.categories.filter { each -> each.id != categoryId },
                    drafts = it.drafts - categoryId,
                    invalidNames = it.invalidNames - categoryId,
                )
            }
        }

    // a change of the categories of the list of the settings, after the ones made before it (GC-4);
    // the list open loads again, its groups follow the categories. A refused change loads the
    // categories again, as the backend has them (GC-3)
    private fun categoriesRequest(
        @StringRes failure: Int,
        call: suspend (GroceryApi, Int) -> Unit,
    ) {
        val id = (state.value as? ListsScreen)?.settings?.id ?: return
        settings(id) { it.copy(categoriesPending = it.categoriesPending + 1, categoriesError = null) }
        scope.launch {
            val done =
                categoryChanges.withLock {
                    request(failure, { error -> settings(id) { it.copy(categoriesError = error) } }) { api -> call(api, id) }
                }
            settings(id) { it.copy(categoriesPending = it.categoriesPending - 1) }
            if (done == null) loadCategories(id)
            load()
        }
    }

    // the category as the settings of the list have it now
    private fun currentCategory(
        listId: Int,
        categoryId: Int,
    ) = (state.value as? ListsScreen)?.settings?.takeIf { it.id == listId }?.categories?.firstOrNull { it.id == categoryId }

    // changes the settings shown, nothing without
    private inline fun settingsOf(change: (ListSettings) -> ListSettings) {
        lists { lists -> lists.settings?.let { lists.copy(settings = change(it)) } ?: lists }
    }

    // changes the settings of the list id, nothing when other settings or none are shown
    private inline fun settings(
        id: Int,
        change: (ListSettings) -> ListSettings,
    ) {
        lists { lists -> lists.settings?.takeIf { it.id == id }?.let { lists.copy(settings = change(it)) } ?: lists }
    }

    fun editSettings(
        name: String,
        shared: Boolean,
        icon: String?,
    ) {
        val lists = state.value as? ListsScreen ?: return
        val settings = lists.settings ?: return
        state.value = lists.copy(settings = settings.copy(name = name, shared = shared, icon = icon))
    }

    // a new list is created and opens, a changed one shows on the home; a failure, e.g. a list of the
    // name exists, stays on the settings with the reason
    fun saveSettings() {
        val name = (state.value as? ListsScreen)?.settings?.name?.trim() ?: return
        if (name.isEmpty() || (state.value as? ListsScreen)?.settings?.busy != false) return
        saveDrafts()
        val lists = state.value as? ListsScreen ?: return
        val settings = lists.settings ?: return
        state.value = lists.copy(settings = settings.copy(busy = true, error = null))
        settingsRequest(settings, if (settings.id == null) R.string.grocery_error_list_not_created else R.string.grocery_form_not_saved) { api ->
            if (settings.id == null) {
                val created = api.createList(name, settings.shared, settings.icon)
                (state.value as? ListsScreen)?.let { state.value = it.copy(lists = it.lists + created, settings = null) }
                show(created.id)
            } else {
                api.updateList(settings.id, name, settings.shared, settings.icon)
                (state.value as? ListsScreen)?.let { state.value = it.copy(settings = null) }
                load()
            }
        }
    }

    // the list of the settings goes with its items, after the user confirmed it (UX-4.1)
    fun deleteList() {
        val lists = state.value as? ListsScreen ?: return
        val settings = lists.settings ?: return
        val id = settings.id ?: return
        if (settings.busy || settings.last) return
        state.value = lists.copy(settings = settings.copy(busy = true, error = null))
        settingsRequest(settings, R.string.grocery_form_not_deleted) { api ->
            api.deleteList(id)
            (state.value as? ListsScreen)?.let { state.value = it.copy(lists = it.lists.filter { list -> list.id != id }, settings = null) }
            load()
        }
    }

    // the photo of an item, a jpeg the ui scaled down (2048 pixels, no exif): stored, then attached;
    // the list loads again, the photo shows then (GROCERY-11 teaches only this user).
    // read makes the jpeg of what the user took or chose, null when it can not be read. It waits for
    // the lists: the camera may come back to an app android ended meanwhile, which starts anew
    fun attachPhoto(
        itemId: Int,
        read: suspend () -> ByteArray?,
    ) {
        scope.launch {
            state.first { it is ListsScreen }
            val jpeg = read()
            if (jpeg == null) {
                lists { it.copy(error = uiText(R.string.grocery_photo_not_read)) }
                return@launch
            }
            photoRequest(itemId, R.string.grocery_photo_not_attached) { api -> api.attachPhoto(itemId, api.uploadPhoto(jpeg)) }
        }
    }

    fun removePhoto(item: GroceryItemResponse) = photoRequest(item.id, R.string.grocery_photo_not_removed) { api -> api.removePhoto(item.id) }

    private fun photoRequest(
        itemId: Int,
        @StringRes failure: Int,
        call: suspend (GroceryApi) -> Unit,
    ) {
        val lists = state.value as? ListsScreen ?: return
        if (lists.photoBusy != null) return
        state.value = lists.copy(photoBusy = itemId, error = null)
        scope.launch {
            val done = request(failure, { error -> lists { it.copy(photoBusy = null, error = error) } }, call)
            lists { it.copy(photoBusy = null) }
            if (done != null) load()
        }
    }

    // the photo of an item in a size, fetched once; null without one, or when it can not be loaded,
    // which the list says only for a photo shown in full
    suspend fun photo(
        item: GroceryItemResponse,
        size: Int?,
        full: Boolean = false,
    ): ByteArray? {
        val fileId = item.photoId ?: return null
        val key = "$fileId-${size ?: "full"}"
        photos[key]?.let { return it }
        val failed: (UiText) -> Unit = { error -> if (full) lists { it.copy(viewing = null, error = error) } }
        return request(R.string.grocery_photo_not_loaded, failed) { it.photo(item.id, fileId, size) }?.also { photos[key] = it }
    }

    // the photo in full over the list, or none
    fun viewPhoto(item: GroceryItemResponse?) = lists { it.copy(viewing = item) }

    // a list of the user's own from the menu of its row on the home, after the user confirmed it
    // (UX-12); not the last one (LIST-1). A failure shows on the home
    fun deleteList(id: Int) {
        val lists = state.value as? ListsScreen ?: return
        if (lists.lists.none { it.id == id && it.mine } || lists.lists.count { it.mine } == 1) return
        scope.launch {
            request(R.string.grocery_form_not_deleted, { error -> lists { it.copy(error = error) } }) { it.deleteList(id) } ?: return@launch
            lists { it.copy(lists = it.lists.filter { list -> list.id != id }) }
            load()
        }
    }

    private fun settingsRequest(
        settings: ListSettings,
        @StringRes failure: Int,
        call: suspend (GroceryApi) -> Unit,
    ) {
        scope.launch { request(failure, { settingsFailed(settings, it) }, call) }
    }

    private fun settingsFailed(
        settings: ListSettings,
        error: UiText?,
    ) {
        val lists = state.value as? ListsScreen ?: return
        val current = lists.settings ?: settings
        state.value = lists.copy(settings = current.copy(busy = false, error = error))
    }

    // what was checked goes to the backend, the field is empty, the list or the home loads
    private fun show(open: Int?) {
        val check = commitPending()
        val lists = state.value as? ListsScreen ?: return
        state.value =
            lists.copy(
                open = open,
                groups = emptyList(),
                categories = emptyList(),
                choosing = null,
                renaming = null,
                deleting = null,
                loading = true,
                error = null,
                itemName = "",
                showBought = false,
            )
        scope.launch { store.update { it.copy(openList = open) } }
        load(after = check)
    }

    // the bought items of the list open shown too, or hidden again (GROCERY-14); the list loads
    fun toggleBought() {
        val lists = state.value as? ListsScreen ?: return
        if (lists.open == null) return
        val check = commitPending()
        lists { it.copy(showBought = !it.showBought, loading = true, error = null) }
        // after the check, so the item it checked shows as the backend has it
        load(after = check)
    }

    fun refresh() {
        val lists = state.value as? ListsScreen ?: return
        state.value = lists.copy(loading = true, error = null)
        load()
    }

    fun typed(name: String) {
        val lists = state.value as? ListsScreen ?: return
        state.value = lists.copy(itemName = name)
    }

    // the name typed goes to the list shown, the field is empty again and the items are loaded anew;
    // a failure keeps the name, to send it again
    fun addItem() {
        val lists = state.value as? ListsScreen ?: return
        val name = lists.itemName.trim()
        val list = lists.open
        if (name.isEmpty() || list == null || lists.adding) return
        state.value = lists.copy(adding = true, error = null)
        scope.launch {
            request(R.string.grocery_list_not_added, { error -> lists { it.copy(adding = false, error = error) } }) { it.addItem(list, name) }
                ?: return@launch
            lists { it.copy(itemName = "", adding = false) }
            load()
        }
    }

    // the user signs out: a check that waits is sent first
    override suspend fun signingOut() {
        commitPending()?.join()
        loading?.cancel()
    }

    override fun sessionEnding() = dropPending()

    override fun clearSession() = photos.clear()

    override fun sessionChanged() = restartListening()

    override fun showApp(stored: Stored) {
        state.value = ListsScreen(stored.deployment ?: stored.apiUrl.orEmpty(), stored.email.orEmpty(), open = stored.openList)
        load()
    }

    // the lists, then the items of the one open, after the request it waits for; a list that is gone
    // leads to the home. A session that ended leads to the sign-in
    private fun load(after: Job? = null) {
        loading?.cancel()
        loading =
            scope.launch {
                after?.join()
                request(R.string.grocery_lists_not_loaded, { error -> lists { it.copy(loading = false, error = error) } }) { api ->
                    val lists = api.lists()
                    val current = state.value as? ListsScreen ?: return@request
                    val wanted = current.open
                    val open = wanted?.takeIf { id -> lists.any { it.id == id } }
                    if (wanted != null && open == null) store.update { it.copy(openList = null) }
                    val items = open?.let { api.items(it, checked = current.showBought) }.orEmpty()
                    val categories = open?.let { api.categories(it) }.orEmpty()
                    lists { current ->
                        // the item whose category is chosen as it is now; gone, e.g. bought by another user, the list shows
                        val choosing = current.choosing?.let { chosen -> items.firstOrNull { it.id == chosen.id } }
                        // the dialogs stay with the item as it is now, also when the list no longer shows it
                        val renaming = current.renaming?.let { renaming -> items.firstOrNull { it.id == renaming.item.id }?.let { renaming.copy(item = it) } ?: renaming }
                        val deleting = current.deleting?.let { deleting -> items.firstOrNull { it.id == deleting.id } ?: deleting }
                        current.copy(
                            lists = lists,
                            open = open,
                            groups = groups(items, categories),
                            categories = categories,
                            choosing = choosing,
                            renaming = renaming,
                            deleting = deleting,
                            loading = false,
                        )
                    }
                }
            }
    }

    // changes the screen of the lists, nothing when another screen is shown
    private inline fun lists(change: (ListsScreen) -> ListsScreen) {
        (state.value as? ListsScreen)?.let { state.value = change(it) }
    }

    // the items by category, in the order of the categories of the list; an item of a category the list
    // no longer has is one without
    private fun groups(
        items: List<GroceryItemResponse>,
        categories: List<GroceryCategoryResponse>,
    ): List<ItemGroup> {
        val order = categories.withIndex().associate { (index, category) -> category.id to index }
        return items
            .groupBy { it.categoryId?.takeIf { id -> id in order } }
            .entries
            .sortedBy { (id, _) -> id?.let { order.getValue(it) } ?: Int.MAX_VALUE }
            .map { (id, grouped) ->
                val category = id?.let { categories[order.getValue(it)] }
                // in a category the items to buy first in the order they were added, then the bought ones, the
                // last bought first (GROCERY-14)
                val ordered = grouped.sortedWith(compareBy<GroceryItemResponse> { it.checkedAt != null }.thenByDescending { it.checkedAt }.thenBy { it.id })
                ItemGroup(id, category?.let { UiText.Plain(it.name) } ?: NO_CATEGORY, category?.icon, ordered)
            }
    }

    // a name of a category the backend takes (GroceryService.MAX_CATEGORY_NAME_LENGTH)
    private fun validCategoryName(name: String) = name.isNotEmpty() && name.length <= MAX_CATEGORY_NAME

    companion object {
        val NO_CATEGORY = uiText(R.string.grocery_list_no_category)

        // the most characters of the name of a category (GroceryService.MAX_CATEGORY_NAME_LENGTH)
        const val MAX_CATEGORY_NAME = 32

        // the longest name of an item the backend takes (GroceryService.MAX_ITEM_NAME_LENGTH)
        const val MAX_ITEM_NAME = 128

        // a name of an item the backend takes, trimmed
        fun validItemName(name: String) = name.trim().let { it.isNotEmpty() && it.length <= MAX_ITEM_NAME }

        // the waits before the stream of the changes is opened again
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 30_000L

        // how many photos are kept fetched
        const val MAX_PHOTOS = 60
    }
}
