package io.pelle.sak.grocery

import androidx.annotation.StringRes
import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.api.model.GroceryItemRequest
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.api.model.GroceryListResponse
import io.pelle.sak.api.model.GrocerySuggestionResponse
import io.pelle.sak.core.Screen
import io.pelle.sak.core.UiText
import io.pelle.sak.core.Undo
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.lists.ChangeStream
import io.pelle.sak.core.lists.ListSummary
import io.pelle.sak.core.lists.ListTexts
import io.pelle.sak.core.lists.ListsModel
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.Updater
import io.pelle.sak.grocery.api.GroceryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

// how long a check can be undone, as in the web app (GROCERY_UNDO_MS); short, while shopping one
// item follows the other
const val UNDO_MS = 1500L

// the logic of the app, without android, on top of core: the lists, the items of the list open with
// suggestions, checks with undo, the settings and categories of a list, the photos of the items.
// apiOf makes the api of a url with a session token or none
class GroceryModel(
    store: SessionStore,
    apiOf: (url: String, token: String?) -> GroceryApi,
    scope: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    allowUrl: Boolean,
    clock: Clock = Clock.systemUTC(),
    // the version of the app, 1.4.2 of a release, latest of every other build
    appVersion: String = "latest",
    updater: Updater? = Updater { error("no updater") },
) : ListsModel<GroceryApi, GroceryListResponse, ListSettings>(
        store,
        apiOf,
        scope,
        allowUrl,
        clock,
        appVersion,
        updater,
        ListTexts(R.string.grocery_error_list_not_created, R.string.grocery_form_not_saved, R.string.grocery_form_not_deleted, R.string.grocery_list_gone),
        GroceryListResponse::summary,
    ) {
    private var loading: Job? = null

    // the check that can still be undone: its item and the timer that sends it to the backend
    private var pending: Pair<GroceryItemResponse, Job>? = null
    private var serial = 0

    // the request of the check sent last; a load waits for it, so it shows the item as checked
    private var checking: Job? = null

    // UNDO_MS, or longer when the accessibility settings of the phone ask for more time; set by the ui
    var undoMs = UNDO_MS

    // the requests of photos, one after the other, none dropped
    private val photoRequests = Mutex()

    // the photo whose items are being read (GROCERY-16), cancelled when its dialog closes
    private var discovering: Job? = null

    // counts up with every list shown: the answer to an item added changes nothing once another
    // list shows
    private var shown = 0

    // the suggestions asked for the name typed last, after a short pause of typing
    private var suggesting: Job? = null

    // the changes of the categories of the settings and their loads, one after the other in the order
    // they were made; a change takes the state it finds when it is its turn
    private val categoryChanges = Mutex()

    // the photos fetched, by file and size, the ones used last kept up to MAX_PHOTO_BYTES
    private val photos = PhotoCache(MAX_PHOTO_BYTES)

    // the changes of the lists once signed in (LIST-4): a change of the list open (or of any list on
    // the home) loads it again, and the categories of its settings shown; a reconnect loads all, the
    // stream may have missed changes
    private val changes =
        ChangeStream(
            scope,
            clock,
            session = {
                state.first { it is ListsScreen }
                store.load().takeIf { it.apiUrl != null }
            },
            changes = { stored, opened -> apiOf(stored.apiUrl.orEmpty(), stored.token).changes(opened) },
            ended = { e, token -> sessionEnded(e, token) },
            onReconnect = {
                load()
                (state.value as? ListsScreen)?.settings?.id?.let { loadCategories(it) }
            },
            onChange = { listId ->
                val lists = state.value as? ListsScreen
                if (lists != null && (lists.open == null || lists.open == listId)) load()
                if (lists?.settings?.id == listId) loadCategories(listId)
            },
        )

    // the app comes to the foreground (LIST-4)
    fun startListening() = changes.start()

    // the app goes to the background: a waiting check is sent now, else it is lost when the process
    // ends. Not on a rotation (SakActivity), the undo stays
    fun stopped() {
        commitPending()
        changes.stop()
    }

    fun stopListening() = changes.stop()

    // the item is struck through and sent after undoMs unless undone; checked again meanwhile, it is
    // undone. A check before it is sent at once (GROCERY-3)
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

    // the check that can be undone is sent now, also when the app closes meanwhile; a failure loads
    // the list as the backend has it. Answers the job of the request, null without a check
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

    // the check that can be undone is dropped unsent, e.g. the session ended
    private fun dropPending() {
        pending?.second?.cancel()
        pending = null
    }

    // the Delete of the menu of an item asks for a confirmation first (UX-12, UX-4.1)
    fun askDelete(item: GroceryItemResponse) = lists { it.copy(deleting = item) }

    fun dismissDelete() = lists { it.copy(deleting = null) }

    // after the user confirmed it (UX-12): the item goes for good, no suggestion comes from it; its
    // check that can still be undone is dropped unsent
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

    fun askRename(item: GroceryItemResponse) = lists { it.copy(renaming = Renaming(item, item.name)) }

    fun editRename(name: String) = lists { lists -> lists.copy(renaming = lists.renaming?.copy(name = name)) }

    // the field of the name was left: its error shows from now on (UX-6)
    fun leaveRename() = lists { lists -> lists.copy(renaming = lists.renaming?.copy(left = true)) }

    fun dismissRename() = lists { it.copy(renaming = null) }

    // an invalid name stays in the dialog with its error (validItemName), an unchanged one is not sent
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

    // GROCERY-15
    fun askQuantity(item: GroceryItemResponse) = lists { it.copy(quantity = Quantity(item, amountText(item.amount), item.unit)) }

    fun editQuantityAmount(amount: String) = lists { lists -> lists.copy(quantity = lists.quantity?.copy(amount = amount)) }

    fun chooseQuantityUnit(unit: String?) = lists { lists -> lists.copy(quantity = lists.quantity?.copy(unit = unit)) }

    // the field of the amount was left: its error shows from now on (UX-6)
    fun leaveQuantity() = lists { lists -> lists.copy(quantity = lists.quantity?.copy(left = true)) }

    fun dismissQuantity() = lists { it.copy(quantity = null) }

    // an invalid amount stays in the dialog with its error; no amount drops the unit too (the backend
    // takes no unit without an amount); an unchanged quantity is not sent
    fun saveQuantity() {
        val lists = state.value as? ListsScreen ?: return
        val quantity = lists.quantity ?: return
        val amount =
            when (val typed = amountOf(quantity.amount)) {
                Amount.Invalid -> {
                    state.value = lists.copy(quantity = quantity.copy(left = true))
                    return
                }
                Amount.None -> null
                is Amount.Of -> typed.value
            }
        val unit = quantity.unit?.takeIf { amount != null }
        state.value = lists.copy(quantity = null)
        val item = quantity.item
        if (amount == item.amount && unit == item.unit) return
        // a check that can still be undone puts the item back with its new quantity
        pending?.let { (waiting, timer) -> if (waiting.id == item.id) pending = waiting.copy(amount = amount, unit = unit) to timer }
        scope.launch {
            request(R.string.grocery_quantity_not_saved, ::failed) {
                it.updateItem(item.id, GroceryItemRequest(item.name, amount, unit, item.categoryId), R.string.grocery_quantity_not_saved)
            } ?: return@launch
            load()
        }
    }

    // a photo of a recipe or a shopping list (GROCERY-16): uploaded, then the AI reads its items for
    // the list open, shown to be corrected; nothing is added yet. read makes the scaled jpeg, null
    // when it can not be read. Like attachPhoto it waits for the lists and drops the photo (discard)
    // when the app starts with the sign-in or no list is open
    fun discover(
        discard: () -> Unit = {},
        read: suspend () -> ByteArray?,
    ) {
        discovering?.cancel()
        discovering =
            scope.launch {
                if (state.first { it !is Screen.Starting } !is ListsScreen) {
                    discard()
                    return@launch
                }
                val listId = (state.value as? ListsScreen)?.open
                if (listId == null) {
                    discard()
                    return@launch
                }
                lists { it.copy(discovery = Discovery(listId), error = null) }
                val jpeg = read()
                if (jpeg == null) {
                    lists { it.copy(discovery = null, error = uiText(R.string.grocery_photo_not_read)) }
                    return@launch
                }
                val failed = { error: UiText -> lists { it.copy(discovery = null, error = error) } }
                val items = request(R.string.grocery_discover_not_read, failed) { api -> api.discoverItems(api.uploadDiscoverPhoto(jpeg)) } ?: return@launch
                discovery(listId) { discovery ->
                    discovery.copy(reading = false, rows = items.mapIndexed { index, item -> DiscoveredRow(index, item.name, amountText(item.amount), item.unit) })
                }
            }
    }

    // a row of the items of the photo added or left out
    fun includeDiscovered(
        index: Int,
        included: Boolean,
    ) = discoveredRow(index) { it.copy(included = included) }

    fun editDiscoveredName(
        index: Int,
        name: String,
    ) = discoveredRow(index) { it.copy(name = name) }

    // the field of the name of a row was left: its error shows from now on (UX-6)
    fun leaveDiscoveredName(index: Int) = discoveredRow(index) { it.copy(nameLeft = true) }

    fun editDiscoveredAmount(
        index: Int,
        amount: String,
    ) = discoveredRow(index) { it.copy(amount = amount) }

    fun leaveDiscoveredAmount(index: Int) = discoveredRow(index) { it.copy(amountLeft = true) }

    fun chooseDiscoveredUnit(
        index: Int,
        unit: String?,
    ) = discoveredRow(index) { it.copy(unit = unit) }

    // the items of the photo close, none added; a photo still read is dropped
    fun dismissDiscovery() {
        discovering?.cancel()
        discovering = null
        lists { it.copy(discovery = null) }
    }

    // the rows checked are added one after the other, also when the app closes meanwhile, without a
    // category, so the backend gives each the one of the latest item of its name. An invalid row
    // shows its error and nothing is added. The dialog closes once all were added; else the rows not
    // added stay, with why
    fun addDiscovered() {
        val lists = state.value as? ListsScreen ?: return
        val discovery = lists.discovery ?: return
        if (discovery.reading || discovery.adding) return
        val chosen = discovery.rows.filter { it.included }
        if (chosen.isEmpty()) return
        if (chosen.any { !it.valid }) {
            state.value = lists.copy(discovery = discovery.copy(submitted = true))
            return
        }
        state.value = lists.copy(discovery = discovery.copy(adding = true, error = null))
        launchLasting {
            val failed =
                chosen.filter { row ->
                    val amount = (amountOf(row.amount) as? Amount.Of)?.value
                    request(R.string.grocery_list_not_added, {}) {
                        it.addItem(discovery.listId, row.name.trim(), amount, row.unit?.takeIf { amount != null })
                    } == null
                }
            lists { current ->
                if (current.discovery?.listId != discovery.listId) return@lists current
                current.copy(
                    discovery =
                        failed.takeIf { it.isNotEmpty() }?.let { rows ->
                            current.discovery.copy(rows = rows, adding = false, error = uiText(R.string.grocery_discover_not_all_added))
                        },
                )
            }
            load()
        }
    }

    // changes the items of the photo for the list listId, nothing when there are none or for another list
    private inline fun discovery(
        listId: Int,
        change: (Discovery) -> Discovery,
    ) {
        lists { lists -> lists.discovery?.takeIf { it.listId == listId }?.let { lists.copy(discovery = change(it)) } ?: lists }
    }

    // changes a row of the items of the photo, none while they are added
    private inline fun discoveredRow(
        index: Int,
        change: (DiscoveredRow) -> DiscoveredRow,
    ) {
        lists { lists ->
            val discovery = lists.discovery?.takeIf { !it.adding && index in it.rows.indices } ?: return@lists lists
            lists.copy(discovery = discovery.copy(rows = discovery.rows.toMutableList().apply { this[index] = change(this[index]) }))
        }
    }

    // whether the next load asks if the AI can read the items of a photo (GET /grocery/settings)
    private var askDiscover = true

    // off when the backend can not say
    private suspend fun discoverOf(api: GroceryApi): Boolean =
        try {
            api.discoverAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    // closes the top dialog or screen, from a list to the home; the next start shows the home
    override fun back() {
        val lists = state.value as? ListsScreen ?: return
        when {
            lists.renaming != null -> state.value = lists.copy(renaming = null)
            lists.quantity != null -> state.value = lists.copy(quantity = null)
            lists.discovery != null -> dismissDiscovery()
            lists.deleting != null -> state.value = lists.copy(deleting = null)
            lists.settings != null -> {
                saveDrafts()
                this.lists { it.copy(settings = null) }
            }
            lists.choosing != null -> state.value = lists.copy(choosing = null)
            lists.open != null -> show(null)
        }
    }

    fun chooseCategory(item: GroceryItemResponse) = lists { it.copy(choosing = item) }

    // the list shows again at once, then with the item in its new group
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

    override fun settingsOpened(id: Int) = loadCategories(id)

    // the names of the categories typed and not yet saved are saved before the settings of the list
    override fun savingSettings() = saveDrafts()

    // pulled down (ANDROID-3) or after a failed load
    fun refreshCategories() {
        val id = (state.value as? ListsScreen)?.settings?.id ?: return
        settings(id) { it.copy(categoriesError = null) }
        loadCategories(id)
    }

    // after the changes made before; the names typed stay. A failure shows why instead of the
    // categories
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

    // the name typed becomes the last category; an empty or too long one shows its error (UX-6)
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

    // kept until it is saved
    fun editCategoryName(
        categoryId: Int,
        name: String,
    ) = settingsOf { it.copy(drafts = it.drafts + (categoryId to name), invalidNames = it.invalidNames - categoryId) }

    // the field of a category is left or done: an empty or too long name shows its error (UX-6), the
    // name the category has is no change
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

    // the settings show it at once; the request takes the category as the settings have it when it is
    // made (its icon included), also when they close before its turn
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

    // the names typed and not yet saved, saved before the settings close; an invalid one is not
    private fun saveDrafts() {
        val settings = (state.value as? ListsScreen)?.settings ?: return
        settings.drafts.forEach { (categoryId, draft) ->
            val name = draft.trim()
            val category = settings.categories.firstOrNull { it.id == categoryId }
            if (category != null && validCategoryName(name) && name != category.name) rename(categoryId, draft, name)
        }
    }

    // one emoji or none: the settings show it at once; the request takes the name the settings show
    // now, so a rename before it stays
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
    // order of all categories goes after the changes made before it. Nothing for a place out of the
    // list or the one it has
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
        // the order as it is now, also when the settings close before its turn; a later move
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

    // a change of the categories of the settings, after the ones made before it; the list open loads
    // again, its groups follow the categories. A refused change loads the categories again
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

    // the photo of an item (GROCERY-12): uploaded, then attached. read makes the scaled jpeg, null
    // when it can not be read. It waits for the lists: the camera may come back to an app android
    // ended meanwhile, which starts over; when it starts with the sign-in (the session ended), the
    // photo is dropped, not kept for a later user, and discard deletes what the camera wrote
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

    // a change of the photo of an item, after the ones before it, none dropped; the row of the
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

    // fetched once per size; null without one or when it can not be loaded, which shows an error only
    // for a photo shown in full
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

    // the photo in full over the list, null for none
    fun viewPhoto(item: GroceryItemResponse?) = lists { it.copy(viewing = item) }

    // what was checked goes to the backend, the field is empty, the list or the home loads
    override fun show(open: Int?) {
        val check = commitPending()
        val lists = state.value as? ListsScreen ?: return
        shown++
        state.value =
            lists.copy(
                open = open,
                adding = false,
                groups = emptyList(),
                categories = emptyList(),
                choosing = null,
                renaming = null,
                quantity = null,
                discovery = null,
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
        discovering?.cancel()
        askDiscover = true
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

    override fun refresh() {
        val lists = state.value as? ListsScreen ?: return
        state.value = lists.copy(loading = true, error = null)
        askDiscover = true
        load()
    }

    // a suggestion chosen goes once the name changes; suggestions follow after a pause of typing
    // (GROCERY-11)
    fun typed(name: String) {
        lists { it.copy(itemName = name, chosen = it.chosen?.takeIf { chosen -> chosen.name == name }) }
        suggest(name)
    }

    // as the web app does; none for an empty text, and none of an older text. A failure shows nothing,
    // the field works without
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

    // with the quantity and the category of the suggestion chosen; a failure keeps the name, to send
    // it again
    fun addItem() {
        val lists = state.value as? ListsScreen ?: return
        val name = lists.itemName.trim()
        val list = lists.open
        if (name.isEmpty() || list == null || lists.adding) return
        val chosen = lists.chosen?.takeIf { it.name.trim() == name }
        suggesting?.cancel()
        state.value = lists.copy(adding = true, error = null, suggestions = emptyList())
        val since = shown
        scope.launch {
            request(R.string.grocery_list_not_added, { error -> if (since == shown) lists { it.copy(adding = false, error = error) } }) {
                it.addItem(list, name, chosen?.amount, chosen?.unit, chosen?.categoryId)
            } ?: return@launch
            // another list shows meanwhile: its field stays as it is
            if (since != shown) return@launch
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

    override fun sessionChanged() = changes.restart()

    override fun showApp(stored: Stored) {
        state.value = ListsScreen(stored.email.orEmpty(), open = stored.openList)
        load()
    }

    // waits for after and the check sent last, so a load a change event starts meanwhile shows the item
    // as checked too; a list that is gone leads to the home, a session that ended to the sign-in
    override fun load(after: Job?) {
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
                    // asked when a list opens and on a pull down, not on every load a change starts
                    val discoverItems =
                        if (open != null && askDiscover) discoverOf(api).also { askDiscover = false } else current.discoverItems
                    lists { current ->
                        // the dialogs belong to the list open: when it went, they go
                        val stays = current.open == open
                        // the item whose category is chosen as it is now; gone, e.g. bought by another user, the list shows
                        val choosing = current.choosing?.let { chosen -> items.firstOrNull { it.id == chosen.id } }
                        // the dialogs stay with the item as it is now, also when the list no longer shows it
                        val renaming =
                            current.renaming?.takeIf { stays }?.let { renaming ->
                                items.firstOrNull { it.id == renaming.item.id }?.let { renaming.copy(item = it) } ?: renaming
                            }
                        val quantity =
                            current.quantity?.takeIf { stays }?.let { quantity ->
                                items.firstOrNull { it.id == quantity.item.id }?.let { quantity.copy(item = it) } ?: quantity
                            }
                        val discovery = current.discovery?.takeIf { stays && it.listId == open }
                        val deleting = current.deleting?.takeIf { stays }?.let { deleting -> items.firstOrNull { it.id == deleting.id } ?: deleting }
                        val viewing = current.viewing?.takeIf { stays }
                        current.copy(
                            lists = lists,
                            open = open,
                            groups = groups(items, categories),
                            categories = categories,
                            choosing = choosing,
                            renaming = renaming,
                            quantity = quantity,
                            discoverItems = discoverItems,
                            discovery = discovery,
                            deleting = deleting,
                            viewing = viewing,
                            loading = false,
                        )
                    }
                }
            }
    }

    override fun listsScreen() = state.value as? ListsScreen

    override fun settingsOf(list: ListSummary?) = list?.let { ListSettings(it.id, it.name, it.shared, it.icon, mine = it.mine) } ?: ListSettings()

    override fun edited(
        settings: ListSettings,
        name: String,
        shared: Boolean,
        icon: String?,
    ) = settings.copy(name = name, shared = shared, icon = icon)

    override fun sending(
        settings: ListSettings,
        busy: Boolean,
        error: UiText?,
    ) = settings.copy(busy = busy, error = error)

    // nothing when another screen is shown
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

    private fun validCategoryName(name: String) = name.isNotEmpty() && name.length <= MAX_CATEGORY_NAME

    companion object {
        val NO_CATEGORY = uiText(R.string.grocery_list_no_category)

        // GroceryService.MAX_CATEGORY_NAME_LENGTH
        const val MAX_CATEGORY_NAME = 32

        // GroceryService.MAX_ITEM_NAME_LENGTH
        const val MAX_ITEM_NAME = 128

        fun validItemName(name: String) = name.trim().let { it.isNotEmpty() && it.length <= MAX_ITEM_NAME }

        // the size of the photo cache
        const val MAX_PHOTO_BYTES = 32L * 1024 * 1024

        // the pause of typing after which the list suggests names (GROCERY-11)
        const val SUGGEST_DELAY_MS = 250L
    }
}
