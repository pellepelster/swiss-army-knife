package io.pelle.sak.grocery

import androidx.annotation.StringRes
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.SakSetup
import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.api.model.GroceryItemRequest
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.api.model.GroceryListResponse
import io.pelle.sak.api.model.GrocerySuggestionResponse
import io.ktor.http.ContentType
import io.pelle.sak.core.ByteCache
import io.pelle.sak.core.UiText
import io.pelle.sak.core.Undo
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.lists.ListTexts
import io.pelle.sak.core.lists.ListsModel
import io.pelle.sak.core.lists.Rename
import io.pelle.sak.core.lists.Renaming
import io.pelle.sak.core.attempt
import io.pelle.sak.core.uiText
import io.pelle.sak.grocery.api.GroceryApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// how long a check can be undone, as in the web app (GROCERY_UNDO_MS); short, while shopping one
// item follows the other
const val UNDO_MS = 1500L

// the texts of the lists of the app, of its model and of the screens of core
internal val GROCERY_TEXTS =
    ListTexts(
        appName = R.string.grocery_app_name,
        create = CoreR.string.lists_form_create,
        allLists = CoreR.string.lists_all_lists,
        chooseIcon = CoreR.string.lists_form_choose_icon,
        noIcon = CoreR.string.lists_form_no_icon,
        deleteList = CoreR.string.lists_form_delete_list,
        notCreated = CoreR.string.lists_not_created,
        notSaved = CoreR.string.lists_form_not_saved,
        notDeleted = CoreR.string.lists_form_not_deleted,
        notLoaded = CoreR.string.lists_not_loaded,
        gone = CoreR.string.lists_list_gone,
    )

// the logic of the app, without android, on top of core: the lists, the items of the list open with
// suggestions, checks with undo, the settings and categories of a list, the photos of the items.
// apiOf makes the api of a url with a session token or none
class GroceryModel(
    setup: SakSetup,
    apiOf: (url: String, token: String?) -> GroceryApi,
) : ListsModel<GroceryApi, GroceryListResponse, ListSettings, ListsScreen>(
        setup,
        apiOf,
        GROCERY_TEXTS,
        GroceryListResponse::summary,
        ::ListSettings,
    ) {
    // UNDO_MS, or longer when the accessibility settings of the phone ask for more time; set by the ui
    var undoMs = UNDO_MS

    // the check that can still be undone, sent once its undo ran out, also when the app closes
    // meanwhile; a failure loads the list as the backend has it
    private val checks =
        undoWindow<GroceryItemResponse>({ undoMs }) { item ->
            listsState { lists ->
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
                lists.copy(groups = groups, struck = lists.struck - item.id, undo = null)
            }
            launchLasting {
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

    // the requests of photos, one after the other, none dropped
    private val photoRequests = Mutex()

    // the photo whose items are being read (GROCERY-16), cancelled when its dialog closes
    private var discovering: Job? = null

    // the suggestions asked for the name typed last, after a short pause of typing
    private var suggesting: Job? = null

    // the changes of the categories of the settings and their loads, one after the other in the order
    // they were made; a change takes the state it finds when it is its turn
    private val categoryChanges = Mutex()

    // the photos fetched, by file and size, the ones used last kept up to MAX_PHOTO_BYTES
    private val photos = ByteCache(MAX_PHOTO_BYTES)

    // a change of the list of the settings shown loads their categories again, so does a reconnect
    override fun listChanged(id: Int) {
        super.listChanged(id)
        if (listsScreen()?.settings?.fields?.id == id) loadCategories(id)
    }

    override fun reconnected() {
        super.reconnected()
        listsScreen()?.settings?.fields?.id?.let { loadCategories(it) }
    }

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
        val serial = checks.start(item)
        listsState { it.copy(struck = it.struck + item.id, undo = Undo(uiText(R.string.grocery_bought, item.name), serial), error = null) }
    }

    fun undo() {
        val item = checks.undo() ?: return
        listsState { it.copy(struck = it.struck - item.id, undo = null) }
    }

    // the Delete of the menu of an item asks for a confirmation first (UX-12, UX-4.1)
    fun askDelete(item: GroceryItemResponse) = listsState { it.copy(deleting = item) }

    fun dismissDelete() = listsState { it.copy(deleting = null) }

    // after the user confirmed it (UX-12): the item goes for good, no suggestion comes from it; its
    // check that can still be undone is dropped unsent
    fun deleteItem(item: GroceryItemResponse) {
        if (state.value !is ListsScreen) return
        if (checks.value?.id == item.id) {
            checks.drop()
            listsState { it.copy(struck = it.struck - item.id, undo = null) }
        }
        // an error of an earlier action goes with this one (as in todo)
        listsState { it.copy(deleting = null, error = null) }
        scope.launch {
            request(R.string.grocery_list_not_deleted, ::failed) { it.deleteItem(item.id) } ?: return@launch
            load()
        }
    }

    fun askRename(item: GroceryItemResponse) = listsState { it.copy(renaming = Renaming(item, item.name)) }

    fun editRename(name: String) = listsState { lists -> lists.copy(renaming = lists.renaming?.copy(name = name)) }

    // the field of the name was left: its error shows from now on (UX-6)
    fun leaveRename() = listsState { lists -> lists.copy(renaming = lists.renaming?.copy(left = true)) }

    fun dismissRename() = listsState { it.copy(renaming = null) }

    // an invalid name stays in the dialog with its error (validItemName), an unchanged one is not sent
    fun renameItem() {
        val lists = state.value as? ListsScreen ?: return
        val renaming = lists.renaming ?: return
        val rename = renaming.submit(renaming.item.name, ::validItemName)
        if (rename is Rename.Invalid) {
            state.value = lists.copy(renaming = rename.renaming)
            return
        }
        state.value = lists.copy(renaming = null)
        if (rename !is Rename.Send) return
        val (item, name) = rename
        listsState { it.copy(error = null) }
        // a check that can still be undone puts the item back under its new name
        checks.update { if (it.id == item.id) it.copy(name = name) else it }
        scope.launch {
            request(R.string.grocery_list_not_renamed, ::failed) {
                it.updateItem(item.id, GroceryItemRequest(name, item.amount, item.unit, item.categoryId))
            } ?: return@launch
            load()
        }
    }

    // GROCERY-15
    fun askQuantity(item: GroceryItemResponse) = listsState { it.copy(quantity = Quantity(item, amountText(item.amount), item.unit)) }

    fun editQuantityAmount(amount: String) = listsState { lists -> lists.copy(quantity = lists.quantity?.copy(amount = amount)) }

    fun chooseQuantityUnit(unit: String?) = listsState { lists -> lists.copy(quantity = lists.quantity?.copy(unit = unit)) }

    // the field of the amount was left: its error shows from now on (UX-6)
    fun leaveQuantity() = listsState { lists -> lists.copy(quantity = lists.quantity?.copy(left = true)) }

    fun dismissQuantity() = listsState { it.copy(quantity = null) }

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
        checks.update { if (it.id == item.id) it.copy(amount = amount, unit = unit) else it }
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
                if (awaitApp() !is ListsScreen) {
                    discard()
                    return@launch
                }
                val listId = (state.value as? ListsScreen)?.open
                if (listId == null) {
                    discard()
                    return@launch
                }
                listsState { it.copy(discovery = Discovery(listId), error = null) }
                val jpeg = read()
                if (jpeg == null) {
                    listsState { it.copy(discovery = null, error = uiText(CoreR.string.photo_not_read)) }
                    return@launch
                }
                val failed = { error: UiText -> listsState { it.copy(discovery = null, error = error) } }
                val items = request(CoreR.string.photo_not_read, failed) { api -> api.discoverItems(api.upload(jpeg, ContentType.Image.JPEG, CoreR.string.photo_not_read).id) } ?: return@launch
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
        listsState { it.copy(discovery = null) }
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
            listsState { current ->
                if (current.discovery?.listId != discovery.listId) return@listsState current
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
    private fun discovery(
        listId: Int,
        change: (Discovery) -> Discovery,
    ) {
        listsState { lists -> lists.discovery?.takeIf { it.listId == listId }?.let { lists.copy(discovery = change(it)) } ?: lists }
    }

    // changes a row of the items of the photo, none while they are added
    private fun discoveredRow(
        index: Int,
        change: (DiscoveredRow) -> DiscoveredRow,
    ) {
        listsState { lists ->
            val discovery = lists.discovery?.takeIf { !it.adding && index in it.rows.indices } ?: return@listsState lists
            lists.copy(discovery = discovery.copy(rows = discovery.rows.toMutableList().apply { this[index] = change(this[index]) }))
        }
    }

    // whether the next load asks if the AI can read the items of a photo (GET /grocery/settings)
    private var askDiscover = true

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
                listsState { it.copy(settings = null) }
            }
            lists.choosing != null -> state.value = lists.copy(choosing = null)
            lists.open != null -> show(null)
        }
    }

    fun chooseCategory(item: GroceryItemResponse) = listsState { it.copy(choosing = item) }

    // the list shows again at once, then with the item in its new group
    fun setCategory(categoryId: Int?) {
        val lists = state.value as? ListsScreen ?: return
        val item = lists.choosing ?: return
        state.value = lists.copy(choosing = null, error = null)
        if (item.categoryId == categoryId) return
        scope.launch {
            request(R.string.grocery_category_not_set, { error -> listsState { it.copy(error = error) } }) { it.setCategory(item.id, categoryId) }
                ?: return@launch
            load()
        }
    }

    override fun settingsOpened(id: Int) = loadCategories(id)

    // the names of the categories typed and not yet saved are saved before the settings of the list
    override fun savingSettings() = saveDrafts()

    // pulled down (ANDROID-3) or after a failed load
    fun refreshCategories() {
        val id = (state.value as? ListsScreen)?.settings?.fields?.id ?: return
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
                val categories = request(R.string.grocery_categories_not_loaded, failed) { it.categories(id) } ?: return@withLock
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
        categoriesRequest(R.string.grocery_categories_not_saved) { api, id ->
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
        categoriesRequest(R.string.grocery_categories_not_saved) { api, id -> api.updateCategory(id, categoryId, renamed.name, renamed.icon) }
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
        categoriesRequest(R.string.grocery_categories_not_saved) { api, id -> api.updateCategory(id, categoryId, changed.name, changed.icon) }
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
        val id = (state.value as? ListsScreen)?.settings?.fields?.id ?: return
        val categories = (state.value as? ListsScreen)?.settings?.categories ?: return
        val from = categories.indexOfFirst { it.id == categoryId }
        if (from < 0 || to !in categories.indices || to == from) return
        val moved = categories.toMutableList().apply { add(to, removeAt(from)) }
        settings(id) { it.copy(categories = moved) }
        // the order as it is now, also when the settings close before its turn; a later move
        // sends its own order after it
        val ids = moved.map { it.id }
        categoriesRequest(R.string.grocery_categories_not_saved) { api, listId -> api.orderCategories(listId, ids) }
    }

    // a category of the list of the settings, after the user confirmed it (UX-4.1); its items lose it
    fun deleteCategory(categoryId: Int) =
        categoriesRequest(R.string.grocery_categories_not_deleted) { api, id ->
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
        val id = (state.value as? ListsScreen)?.settings?.fields?.id ?: return
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
    private fun settingsOf(change: (ListSettings) -> ListSettings) {
        listsState { lists -> lists.settings?.let { lists.copy(settings = change(it)) } ?: lists }
    }

    // changes the settings of the list id, nothing when other settings or none are shown
    private fun settings(
        id: Int,
        change: (ListSettings) -> ListSettings,
    ) {
        listsState { lists -> lists.settings?.takeIf { it.fields.id == id }?.let { lists.copy(settings = change(it)) } ?: lists }
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
            if (awaitApp() !is ListsScreen) {
                discard()
                return@launch
            }
            val jpeg = read()
            if (jpeg == null) {
                listsState { it.copy(error = uiText(CoreR.string.photo_not_read)) }
                return@launch
            }
            photoRequest(itemId, R.string.grocery_photo_not_attached) { api -> api.attachPhoto(itemId, api.upload(jpeg, ContentType.Image.JPEG, R.string.grocery_photo_not_attached).id) }
        }
    }

    fun removePhoto(item: GroceryItemResponse) = photoRequest(item.id, R.string.grocery_photo_not_removed) { api -> api.removePhoto(item) }

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
                    listsState { it.copy(photoBusy = itemId, error = null) }
                    request(failure, { error -> listsState { it.copy(error = error) } }, call).also { listsState { it.copy(photoBusy = null) } }
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
        photos[key]?.let { return it }
        val failed: (UiText) -> Unit = { error -> if (full) listsState { it.copy(viewing = null, error = error) } }
        return request(CoreR.string.photo_not_loaded, failed) { it.photo(item.id, fileId, size) }?.also { photos.put(key, it) }
    }

    // the photo in full over the list, null for none
    fun viewPhoto(item: GroceryItemResponse?) = listsState { it.copy(viewing = item) }

    // the field is empty, the dialogs and what is read or suggested go
    override fun opened(
        lists: ListsScreen,
        open: Int?,
    ): ListsScreen {
        suggesting?.cancel()
        discovering?.cancel()
        askDiscover = true
        return lists.copy(
            groups = emptyList(),
            categories = emptyList(),
            choosing = null,
            renaming = null,
            quantity = null,
            discovery = null,
            deleting = null,
            viewing = null,
            itemName = "",
            suggestions = emptyList(),
            showBought = false,
        )
    }

    // the bought items of the list open shown too, or hidden again (GROCERY-14); the list loads
    fun toggleBought() {
        val lists = state.value as? ListsScreen ?: return
        if (lists.open == null) return
        // the load waits for the check, so the item it checked shows as the backend has it
        checks.commit()
        listsState { it.copy(showBought = !it.showBought, loading = true, error = null) }
        load()
    }

    override fun refresh() {
        askDiscover = true
        super.refresh()
    }

    // the AI of the grocery lists turned on or off by the user: the robot follows
    override fun userSettingsChanged() {
        scope.launch {
            val settings = request(CoreR.string.photo_not_read, {}) { it.settings() } ?: return@launch
            listsState { it.copy(discoverItems = settings.discoverItems, aiConfigured = settings.aiConfigured) }
        }
    }

    // suggestions follow after a pause of typing (GROCERY-11)
    fun typed(name: String) {
        listsState { it.copy(itemName = name) }
        suggest(name)
    }

    // as the web app does; none for an empty text, and none of an older text. A failure shows nothing,
    // the field works without
    private fun suggest(text: String) {
        suggesting?.cancel()
        val listId = (state.value as? ListsScreen)?.open ?: return
        if (text.isBlank()) {
            listsState { it.copy(suggestions = emptyList()) }
            return
        }
        suggesting =
            scope.launch {
                delay(SUGGEST_DELAY_MS)
                val found = request(R.string.grocery_list_items_not_loaded, {}) { it.suggestions(listId, text.trim()) } ?: return@launch
                listsState { if (it.open == listId && it.itemName == text) it.copy(suggestions = found) else it }
            }
    }

    // a suggestion chosen is added at once, with its quantity and category
    fun chooseSuggestion(suggestion: GrocerySuggestionResponse) = addItem(suggestion)

    // the name typed, or a suggestion chosen with its quantity and category; a failure keeps the
    // name in the field, to send it again (addEntry)
    fun addItem(chosen: GrocerySuggestionResponse? = null) {
        val lists = listsScreen() ?: return
        val name = (chosen?.name ?: lists.itemName).trim()
        val sending = { current: ListsScreen ->
            suggesting?.cancel()
            current.copy(itemName = chosen?.name ?: current.itemName, suggestions = emptyList())
        }
        addEntry(name, R.string.grocery_list_not_added, sending, { it.copy(itemName = "") }) { api, list ->
            api.addItem(list, name, chosen?.amount, chosen?.unit, chosen?.categoryId)
        }
    }

    override fun sessionGone() {
        photos.clear()
        super.sessionGone()
    }

    override fun appScreen(
        user: String,
        open: Int?,
    ) = ListsScreen(user, open = open)

    // the items of the list open with its categories, as the list shows them now (the bought ones
    // too); whether the AI reads the items of a photo is asked when a list opens and on a pull down,
    // not on every load a change starts
    override suspend fun loaded(
        api: GroceryApi,
        open: Int?,
    ): (ListsScreen) -> ListsScreen {
        val items = open?.let { api.items(it, checked = listsScreen()?.showBought == true) }.orEmpty()
        val categories = open?.let { api.categories(it) }.orEmpty()
        // asked and not answered, there is no robot
        val asked = open != null && askDiscover
        val settings = if (asked) attempt { api.settings() }.also { askDiscover = false } else null
        return { current ->
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
                groups = groups(items, categories),
                categories = categories,
                choosing = choosing,
                renaming = renaming,
                quantity = quantity,
                discoverItems = if (asked) settings?.discoverItems ?: false else current.discoverItems,
                aiConfigured = if (asked) settings?.aiConfigured ?: false else current.aiConfigured,
                discovery = discovery,
                deleting = deleting,
                viewing = viewing,
            )
        }
    }

    override fun listsScreen() = state.value as? ListsScreen


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
