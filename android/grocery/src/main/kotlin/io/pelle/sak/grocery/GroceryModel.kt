package io.pelle.sak.grocery

import androidx.annotation.StringRes
import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.api.model.GroceryItemRequest
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.api.model.GrocerySuggestionResponse
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.Screen
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.Updater
import io.pelle.sak.grocery.api.GroceryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

// how long a check can be undone, the same as in the web app (GROCERY_UNDO_MS): while shopping one
// item follows the other
const val UNDO_MS = 1500L

// the logic of the app, without android, on top of what core does (the deployment, the sign-in, the
// session): the lists on the home and the items to buy of the list open, an item added from the field
// at the top with the suggestions of the list, a checked item bought once its undo ran out, the
// settings and the categories of a list, the photos of the items. apiOf makes the api of a url with a
// session token or none
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

    // the request of the check sent last, which a load waits for, so it shows the item as the
    // backend has it after the check (AN-52)
    private var checking: Job? = null

    // how long a check can be undone: UNDO_MS, longer when the accessibility settings of the phone ask
    // for more time for a control (AN-34), set by the ui
    var undoMs = UNDO_MS

    // the requests of photos, one after the other, none dropped (AN-32)
    private val photoRequests = Mutex()

    // the suggestions asked for the name typed last, after a short pause of typing
    private var suggesting: Job? = null

    // the changes of the categories of the settings and their loads, one after the other in the order
    // they were made; a change takes the state it finds when it is its turn
    private val categoryChanges = Mutex()

    // the photos fetched, by their file and size, the ones used last kept up to MAX_PHOTO_BYTES (AN-21)
    private val photos = PhotoCache(MAX_PHOTO_BYTES)

    // the app comes to the foreground: it hears the changes of the lists once the user is signed in,
    // a change of the list open, or of any list on the home, loads it again (LIST-4). A stream that
    // ends is opened again, after a longer wait each time, up to MAX_RETRY_DELAY_MS; the wait starts
    // short again only after a stream stayed open STABLE_STREAM, so one that closes at once does not
    // load everything every second (AN-52)
    fun startListening() {
        if (listening?.isActive == true) return
        listening =
            scope.launch {
                var wait = RETRY_DELAY_MS
                while (isActive) {
                    state.first { it is ListsScreen }
                    val stored = store.load()
                    val url = stored.apiUrl ?: return@launch
                    var openedAt: Instant? = null
                    try {
                        apiOf(url, stored.token)
                            .changes(opened = {
                                openedAt = clock.instant()
                                // the changes meanwhile, while the app was away or the stream lost: the
                                // list or the home, and the categories of the settings shown (AN-33)
                                if (listenedBefore) {
                                    load()
                                    (state.value as? ListsScreen)?.settings?.id?.let { loadCategories(it) }
                                }
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
                        if (e.status == 401) sessionEnded(e, stored.token)
                    } catch (_: Exception) {
                        // the connection ended, it is opened again
                    }
                    if (openedAt?.let { Duration.between(it, clock.instant()) >= STABLE_STREAM } == true) wait = RETRY_DELAY_MS
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
    }

    // the app goes to the background: a check that waits is sent now, it would be lost with the app
    // (AN-29), and the stream is closed
    fun stopped() {
        commitPending()
        stopListening()
    }

    // the stream is closed
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
                delay(undoMs)
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

    // the check that can be undone goes to the backend now, also when the app closes meanwhile
    // (AN-29); a failure brings the list back as the backend has it. The job of the request, null
    // without a check
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
        return launchLasting {
            request(R.string.grocery_list_not_checked, ::failed) { api ->
                // bought already, e.g. by another device in the undo window, or deleted: done, no error
                try {
                    api.checkItem(item.id)
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
            }
        }.also { checking = it }
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

    // the settings of a list the user sees, their own or a shared one of another user (LIST-2), from
    // its menu on the home or from the list itself; back and save return there, a deleted list leads
    // to the home
    fun openSettings(id: Int) {
        val lists = state.value as? ListsScreen ?: return
        val list = lists.lists.firstOrNull { it.id == id } ?: return
        val last = list.mine && lists.lists.count { it.mine } == 1
        state.value = lists.copy(settings = ListSettings(list.id, list.name, list.shared, list.icon, last, mine = list.mine))
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

    // the name of a category: the settings show it at once, the request takes the category as the
    // settings have it now, its icon included, also when they close before its turn (AN-6)
    private fun rename(
        categoryId: Int,
        draft: String,
        name: String,
    ) {
        val category = currentCategory(categoryId) ?: return
        val renamed = category.copy(name = name)
        settingsOf { settings ->
            settings.copy(
                categories = settings.categories.map { if (it.id == categoryId) renamed else it },
                // a name typed again meanwhile stays
                drafts = if (settings.drafts[categoryId] == draft) settings.drafts - categoryId else settings.drafts,
            )
        }
        categoriesRequest(R.string.grocery_category_not_saved) { api, id -> api.updateCategory(id, categoryId, renamed.name, renamed.icon) }
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

    // the icon of a category, one emoji or none: the settings show it at once, the request takes the
    // name the settings show now, so a rename before it stays (AN-6)
    fun setCategoryIcon(
        categoryId: Int,
        icon: String?,
    ) {
        val category = currentCategory(categoryId) ?: return
        if (category.icon == icon) return
        val changed = category.copy(icon = icon)
        settingsOf { settings -> settings.copy(categories = settings.categories.map { if (it.id == categoryId) changed else it }) }
        categoriesRequest(R.string.grocery_category_not_saved) { api, id -> api.updateCategory(id, categoryId, changed.name, changed.icon) }
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
        val moved = categories.toMutableList().apply { add(to, removeAt(from)) }
        settings(id) { it.copy(categories = moved) }
        // the order as it is now, also when the settings close before its turn (AN-6); a later move
        // sends its own order after it
        val ids = moved.map { it.id }
        categoriesRequest(R.string.grocery_category_not_saved) { api, listId -> api.orderCategories(listId, ids) }
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

    // the category as the settings shown have it now
    private fun currentCategory(categoryId: Int) = (state.value as? ListsScreen)?.settings?.categories?.firstOrNull { it.id == categoryId }

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
        // only the owner shares or unshares a list
        state.value = lists.copy(settings = settings.copy(name = name, shared = if (settings.mine) shared else settings.shared, icon = icon))
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
                try {
                    api.updateList(settings.id, name, settings.shared, settings.icon)
                } catch (e: ApiException) {
                    // the owner unshared or deleted it while the settings were open: they close
                    if (e.status != 404) throw e
                    (state.value as? ListsScreen)?.let { state.value = it.copy(settings = null) }
                    failed(uiText(R.string.grocery_list_gone))
                    return@settingsRequest
                }
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
        if (settings.busy || settings.last || !settings.mine) return
        state.value = lists.copy(settings = settings.copy(busy = true, error = null))
        settingsRequest(settings, R.string.grocery_form_not_deleted) { api ->
            api.deleteList(id)
            (state.value as? ListsScreen)?.let { state.value = it.copy(lists = it.lists.filter { list -> list.id != id }, settings = null) }
            load()
        }
    }

    // the photo of an item, a jpeg the ui scaled down (2048 pixels, no exif): stored, then attached;
    // the list loads again, the photo shows then (GROCERY-12). read makes the jpeg of what the user took
    // or chose, null when it can not be read. It waits for the lists: the camera may come back to an
    // app android ended meanwhile, which starts anew; when it starts with the sign-in instead (the
    // session ended), the photo is dropped, not kept for a later sign-in, maybe of another user, and
    // discard deletes what the camera wrote (AN-52)
    fun attachPhoto(
        itemId: Int,
        discard: () -> Unit = {},
        read: suspend () -> ByteArray?,
    ) {
        scope.launch {
            if (state.first { it !is Screen.Starting } !is ListsScreen) {
                discard()
                return@launch
            }
            val jpeg = read()
            if (jpeg == null) {
                lists { it.copy(error = uiText(R.string.grocery_photo_not_read)) }
                return@launch
            }
            photoRequest(itemId, R.string.grocery_photo_not_attached) { api -> api.attachPhoto(itemId, api.uploadPhoto(jpeg)) }
        }
    }

    fun removePhoto(item: GroceryItemResponse) = photoRequest(item.id, R.string.grocery_photo_not_removed) { api -> api.removePhoto(item.id) }

    // a change of the photo of an item, after the ones before it, none dropped (AN-32); the row of the
    // item shows it busy while it is sent
    private fun photoRequest(
        itemId: Int,
        @StringRes failure: Int,
        call: suspend (GroceryApi) -> Unit,
    ) {
        if (state.value !is ListsScreen) return
        scope.launch {
            val done =
                photoRequests.withLock {
                    lists { it.copy(photoBusy = itemId, error = null) }
                    request(failure, { error -> lists { it.copy(error = error) } }, call).also { lists { it.copy(photoBusy = null) } }
                }
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
        photos.get(key)?.let { return it }
        val failed: (UiText) -> Unit = { error -> if (full) lists { it.copy(viewing = null, error = error) } }
        return request(R.string.grocery_photo_not_loaded, failed) { it.photo(item.id, fileId, size) }?.also { photos.put(key, it) }
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
                viewing = null,
                loading = true,
                error = null,
                itemName = "",
                suggestions = emptyList(),
                chosen = null,
                showBought = false,
            )
        suggesting?.cancel()
        scope.launch { store.update { it.withOpenList(open) } }
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

    // the name typed into the field at the top; a suggestion chosen goes once the name changes. The
    // list suggests names for it after a short pause of typing (GROCERY-11)
    fun typed(name: String) {
        lists { it.copy(itemName = name, chosen = it.chosen?.takeIf { chosen -> chosen.name == name }) }
        suggest(name)
    }

    // the names the list suggests for the text, as the web app does; none for an empty text, the ones
    // of a text typed meanwhile do not show. A failure shows nothing, the field works without
    private fun suggest(text: String) {
        suggesting?.cancel()
        val listId = (state.value as? ListsScreen)?.open ?: return
        if (text.isBlank()) {
            lists { it.copy(suggestions = emptyList()) }
            return
        }
        suggesting =
            scope.launch {
                delay(SUGGEST_DELAY_MS)
                val found = request(R.string.grocery_list_items_not_loaded, {}) { it.suggestions(listId, text.trim()) } ?: return@launch
                lists { if (it.open == listId && it.itemName == text) it.copy(suggestions = found) else it }
            }
    }

    // a suggestion chosen: its name goes into the field, its quantity and category come with the item
    fun chooseSuggestion(suggestion: GrocerySuggestionResponse) {
        suggesting?.cancel()
        lists { it.copy(itemName = suggestion.name, chosen = suggestion, suggestions = emptyList()) }
    }

    // the name typed goes to the list shown, with the quantity and the category of the suggestion
    // chosen; the field is empty again and the items are loaded anew; a failure keeps the name, to send
    // it again
    fun addItem() {
        val lists = state.value as? ListsScreen ?: return
        val name = lists.itemName.trim()
        val list = lists.open
        if (name.isEmpty() || list == null || lists.adding) return
        val chosen = lists.chosen?.takeIf { it.name.trim() == name }
        suggesting?.cancel()
        state.value = lists.copy(adding = true, error = null, suggestions = emptyList())
        scope.launch {
            request(R.string.grocery_list_not_added, { error -> lists { it.copy(adding = false, error = error) } }) {
                it.addItem(list, name, chosen?.amount, chosen?.unit, chosen?.categoryId)
            } ?: return@launch
            lists { it.copy(itemName = "", chosen = null, adding = false) }
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

    // the lists, then the items of the one open, after the request it waits for and the check sent
    // last, so a load that a change event starts meanwhile shows the item as the backend has it after
    // the check too (AN-52); a list that is gone leads to the home. A session that ended leads to the
    // sign-in
    private fun load(after: Job? = null) {
        loading?.cancel()
        val check = checking
        loading =
            scope.launch {
                after?.join()
                check?.join()
                request(R.string.grocery_lists_not_loaded, { error -> lists { it.copy(loading = false, error = error) } }) { api ->
                    val lists = api.lists()
                    val current = state.value as? ListsScreen ?: return@request
                    val wanted = current.open
                    val open = wanted?.takeIf { id -> lists.any { it.id == id } }
                    if (wanted != null && open == null) store.update { it.withOpenList(null) }
                    val items = open?.let { api.items(it, checked = current.showBought) }.orEmpty()
                    val categories = open?.let { api.categories(it) }.orEmpty()
                    lists { current ->
                        // the dialogs belong to the list open: when it went, they go (AN-31)
                        val stays = current.open == open
                        // the item whose category is chosen as it is now; gone, e.g. bought by another user, the list shows
                        val choosing = current.choosing?.let { chosen -> items.firstOrNull { it.id == chosen.id } }
                        // the dialogs stay with the item as it is now, also when the list no longer shows it
                        val renaming =
                            current.renaming?.takeIf { stays }?.let { renaming ->
                                items.firstOrNull { it.id == renaming.item.id }?.let { renaming.copy(item = it) } ?: renaming
                            }
                        val deleting = current.deleting?.takeIf { stays }?.let { deleting -> items.firstOrNull { it.id == deleting.id } ?: deleting }
                        val viewing = current.viewing?.takeIf { stays }
                        current.copy(
                            lists = lists,
                            open = open,
                            groups = groups(items, categories),
                            categories = categories,
                            choosing = choosing,
                            renaming = renaming,
                            deleting = deleting,
                            viewing = viewing,
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

        // the waits before the stream of the changes is opened again, and how long one stays open before
        // the wait starts short again
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 30_000L
        val STABLE_STREAM: Duration = Duration.ofSeconds(30)

        // how many bytes of photos are kept fetched
        const val MAX_PHOTO_BYTES = 32L * 1024 * 1024

        // the pause of typing after which the list suggests names (GROCERY-11)
        const val SUGGEST_DELAY_MS = 250L
    }
}
