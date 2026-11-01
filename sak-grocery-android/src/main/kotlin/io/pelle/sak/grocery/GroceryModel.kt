package io.pelle.sak.grocery

import androidx.annotation.StringRes
import io.pelle.sak.grocery.api.ApiException
import io.pelle.sak.grocery.api.GroceryApi
import io.pelle.sak.grocery.api.GroceryCategoryResponse
import io.pelle.sak.grocery.api.GroceryItemResponse
import io.pelle.sak.grocery.api.GroceryListResponse
import io.pelle.sak.grocery.session.PendingLogin
import io.pelle.sak.grocery.session.SessionStore
import io.pelle.sak.grocery.session.Stored
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
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

// how long a check can be undone, the same as in the web app (GROCERY_UNDO_MS): while shopping one
// item follows the other
const val UNDO_MS = 1500L

// how long a login link the app asked for is taken (the login token of the backend lives as long)
val PENDING_FOR: Duration = Duration.ofMinutes(15)

// the items of a category, in the order of the categories of the backend, the ones without one last
data class ItemGroup(
    // the name of the category, null for the items without one; the key of the group
    val category: String?,
    // the label of the category the backend sent, or no category
    val label: UiText,
    val items: List<GroceryItemResponse>,
)

// the check that can still be undone; serial starts the bar of the time left anew for every check
data class Undo(
    val text: UiText,
    val serial: Int,
)

// the settings of a list the user creates (no id) or changes, one of their own: its name, whether
// all users see it and its icon, one emoji
data class ListSettings(
    val id: Int? = null,
    val name: String = "",
    val shared: Boolean = false,
    val icon: String? = null,
    // the only list of the user's own, which can not be deleted (LIST-1)
    val last: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
)

// the version of the app the deployment runs, newer than the app; busy while it is downloaded and
// handed to the installer of android
data class AppUpdate(
    val version: String,
    val busy: Boolean = false,
    val error: UiText? = null,
)

// the update of the app to a version of sak: the apk of its release downloaded, checked and handed
// to the installer of android, which asks the user; a failure is an exception, an UpdateException
// with the reason the user reads
fun interface Updater {
    suspend fun install(version: String)
}

class UpdateException(
    val text: UiText,
) : Exception(text.toString())

// the version of the deployment when it is newer than the one of the app, both released versions
// (1.4.2); a build of no release (latest) is never updated, nor is a deployment without a version
internal fun newer(
    app: String,
    deployment: String?,
): String? {
    fun parts(version: String?) = version?.let { RELEASE.matchEntire(it) }?.groupValues?.drop(1)?.map { it.toInt() }
    val current = parts(app) ?: return null
    val offered = parts(deployment) ?: return null
    val compared = current.zip(offered).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
    return deployment.takeIf { compared < 0 }
}

// the sizes the backend scales a photo to, on its longer side (FileService.SCALED_SIZES)
val SCALED_SIZES = listOf(128, 256, 480, 1024)

// the size to ask for of a photo that shows at pixels on the screen: the smallest scaled size that
// is at least as large, null when none is, the photo itself then
fun scaledSize(pixels: Int): Int? = SCALED_SIZES.firstOrNull { it >= pixels }

// a released version, 1.4.2, not latest
internal fun isRelease(version: String?) = version != null && RELEASE.matches(version)

private val RELEASE = Regex("""(\d+)\.(\d+)\.(\d+)""")

// what the app shows
sealed interface Screen {
    data object Starting : Screen

    // the deployment, its base domain typed in
    data class Deployment(
        val input: String = "",
        val busy: Boolean = false,
        val error: UiText? = null,
    ) : Screen

    // the sign-in follows the user mode (UX-7): in the multi user mode the e-mail login with a link to
    // the password login, in the single user mode only the password login
    data class SignIn(
        val deployment: String,
        // the multi user mode, null until the deployment answered
        val multiUser: Boolean?,
        // the password login is shown: chosen, or the only one in the single user mode
        val withPassword: Boolean = multiUser == false,
        val busy: Boolean = false,
        val error: UiText? = null,
        // the address a link went to, the app waits for it
        val linkSentTo: String? = null,
    ) : Screen

    data class Lists(
        val deployment: String,
        // who is signed in: their name, else the e-mail address, or admin
        val user: String,
        val lists: List<GroceryListResponse> = emptyList(),
        // the id of the list open, null on the home, which shows all lists
        val open: Int? = null,
        val groups: List<ItemGroup> = emptyList(),
        val loading: Boolean = true,
        val error: UiText? = null,
        // the name typed into the field at the top, kept while it is sent (GROCERY-2)
        val itemName: String = "",
        val adding: Boolean = false,
        // the items checked while they can be undone, struck through until they go
        val struck: Set<Int> = emptySet(),
        val undo: Undo? = null,
        // the screen of the settings of a list, a new one or one of the user's own, over the home
        val settings: ListSettings? = null,
        // the newer version of the app the deployment runs, offered on the home
        val update: AppUpdate? = null,
        // the answer of the check for updates of the menu while there is no update to offer, e.g. that
        // the app is up-to-date; on the home until the user dismisses it
        val updateNotice: UiText? = null,
        // the item whose photo is being attached or removed
        val photoBusy: Int? = null,
        // the item whose photo shows in full, over the list
        val viewing: GroceryItemResponse? = null,
    ) : Screen
}

// the logic of the app, without android: the deployment, the sign-in by link or password, the lists
// on the home and the items to buy of the list open, an item added from the field at the top, a checked item deleted once
// its undo ran out. apiOf makes the api of a url with a session token or none
class GroceryModel(
    private val store: SessionStore,
    private val apiOf: (url: String, token: String?) -> GroceryApi,
    private val scope: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    private val allowUrl: Boolean,
    private val clock: Clock = Clock.systemUTC(),
    // the version of the app, 1.4.2 of a release, latest of every other build
    private val appVersion: String = "latest",
    private val updater: Updater = Updater { error("no updater") },
) {
    private val state = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = state.asStateFlow()

    private var loading: Job? = null

    // the stream of the changes while the app is in the foreground (LIST-4); whether it was open
    // before, a new one may have missed changes
    private var listening: Job? = null
    private var listenedBefore = false

    // the check that can still be undone: its item and the timer that sends it to the backend
    private var pending: Pair<GroceryItemResponse, Job>? = null
    private var serial = 0

    // start(), which a link of a cold start waits for, so its screen does not come after the lists
    private var starting: Job? = null

    // the categories of the deployment, asked for once a session: they do not change
    private var categories: List<GroceryCategoryResponse>? = null

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
                    state.first { it is Screen.Lists }
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
                                val lists = state.value as? Screen.Lists
                                if (lists != null && (lists.open == null || lists.open == listId)) load()
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

    // where the app was left: the lists while the session lasts, else the sign-in, else the deployment
    fun start() {
        starting =
            scope.launch {
                val stored = store.load()
                when {
                    stored.apiUrl == null -> state.value = Screen.Deployment()
                    signedIn(stored) -> showLists(stored)
                    else -> showSignIn(stored)
                }
            }
    }

    fun connect(input: String) {
        val url = apiUrl(input, allowUrl)
        if (url == null) {
            state.value = Screen.Deployment(input, error = uiText(R.string.error_base_domain))
            return
        }
        state.value = Screen.Deployment(input, busy = true)
        scope.launch {
            try {
                val info = apiOf(url, null).info()
                val stored = Stored(apiUrl = url, deployment = info.name)
                store.save(stored)
                showSignIn(stored)
            } catch (e: Exception) {
                state.value = Screen.Deployment(input, error = failure(e, R.string.error_no_deployment))
            }
        }
    }

    // back to the deployment, the session and a pending login go
    fun changeDeployment() =
        scope.launch {
            store.save(Stored())
            categories = null
            photos.clear()
            restartListening()
            state.value = Screen.Deployment()
        }

    fun requestLink(email: String) {
        val signIn = state.value as? Screen.SignIn ?: return
        val address = email.trim()
        if (address.isEmpty()) return
        state.value = signIn.copy(busy = true, error = null)
        scope.launch {
            val stored = store.load()
            val url = stored.apiUrl ?: return@launch
            try {
                apiOf(url, null).requestLink(address)
                store.update { it.copy(pending = PendingLogin(url, clock.instant())) }
                state.value = signIn.copy(busy = false, linkSentTo = address)
            } catch (e: Exception) {
                state.value = signIn.copy(busy = false, error = failure(e, R.string.error_link_not_requested))
            }
        }
    }

    // between the e-mail login and the password login, in the multi user mode
    fun usePassword(withPassword: Boolean) {
        val signIn = state.value as? Screen.SignIn ?: return
        if (signIn.multiUser == true) state.value = signIn.copy(withPassword = withPassword, error = null)
    }

    fun signInWithPassword(password: String) {
        val signIn = state.value as? Screen.SignIn ?: return
        if (password.isEmpty()) return
        state.value = signIn.copy(busy = true, error = null)
        scope.launch {
            val stored = store.load()
            val url = stored.apiUrl ?: return@launch
            try {
                val session = apiOf(url, null).passwordLogin(password)
                signedIn(session.sessionToken, session.email, session.expiresAt)
            } catch (e: Exception) {
                state.value = signIn.copy(busy = false, error = failure(e, R.string.error_password_not_checked))
            }
        }
    }

    // the token of a login link the signin page of the web app handed on; taken only for a login
    // this app asked for, on the same deployment, within PENDING_FOR
    fun signInWithLink(token: String) =
        scope.launch {
            starting?.join()
            val stored = store.load()
            val pending = stored.pending
            val url = stored.apiUrl
            val fresh = pending != null && url == pending.apiUrl && Duration.between(pending.startedAt, clock.instant()) <= PENDING_FOR
            if (url == null || !fresh) {
                if (url == null) {
                    state.value = Screen.Deployment(error = NOT_ASKED)
                } else {
                    showSignIn(stored, error = NOT_ASKED)
                }
                return@launch
            }
            (state.value as? Screen.SignIn)?.let { state.value = it.copy(busy = true, error = null) }
            try {
                val session = apiOf(url, null).exchange(token)
                signedIn(session.sessionToken, session.email, session.expiresAt)
            } catch (e: Exception) {
                showSignIn(stored, error = failure(e, R.string.error_link_not_used))
            }
        }

    // the item is bought and struck through, after UNDO_MS the backend learns it and it goes from the
    // list, unless it is undone; checked again while it can be undone, it is not bought after all. A
    // check before goes to the backend at once (GROCERY-3)
    fun check(item: GroceryItemResponse) {
        val lists = state.value as? Screen.Lists ?: return
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
        (state.value as? Screen.Lists)?.let {
            state.value = it.copy(struck = it.struck + item.id, undo = Undo(uiText(R.string.grocery_bought, item.name), ++serial), error = null)
        }
    }

    fun undo() {
        val (item, timer) = pending ?: return
        timer.cancel()
        pending = null
        (state.value as? Screen.Lists)?.let { state.value = it.copy(struck = it.struck - item.id, undo = null) }
    }

    // the check that can be undone goes to the backend now; a failure brings the list back as the
    // backend has it. The job of the request, null without a check
    private fun commitPending(): Job? {
        val (item, timer) = pending ?: return null
        pending = null
        timer.cancel()
        (state.value as? Screen.Lists)?.let { lists ->
            val groups = lists.groups.map { it.copy(items = it.items.filter { each -> each.id != item.id }) }.filter { it.items.isNotEmpty() }
            state.value = lists.copy(groups = groups, struck = lists.struck - item.id, undo = null)
        }
        return scope.launch { request(R.string.grocery_list_not_checked, ::failed) { it.checkItem(item.id) } }
    }

    // the check that can be undone goes without being sent, e.g. the session ended
    private fun dropPending() {
        pending?.second?.cancel()
        pending = null
    }

    private fun failed(error: UiText) {
        val lists = state.value as? Screen.Lists ?: return
        state.value = lists.copy(error = error)
        load()
    }

    // a list of the home opens, the next start opens it again (LIST-3)
    fun open(id: Int) {
        val lists = state.value as? Screen.Lists ?: return
        if (lists.open != null || lists.lists.none { it.id == id }) return
        show(id)
    }

    // from a list or the settings back to the home, the next start shows the home
    fun back() {
        val lists = state.value as? Screen.Lists ?: return
        when {
            lists.settings != null -> state.value = lists.copy(settings = null)
            lists.open != null -> show(null)
        }
    }

    // the settings of a new list, from the home
    fun newList() {
        val lists = state.value as? Screen.Lists ?: return
        if (lists.open == null) state.value = lists.copy(settings = ListSettings())
    }

    // the settings of a list of the user's own, from its menu on the home or from the list itself;
    // back and save return there, a deleted list leads to the home
    fun openSettings(id: Int) {
        val lists = state.value as? Screen.Lists ?: return
        val list = lists.lists.firstOrNull { it.id == id && it.mine } ?: return
        val last = lists.lists.count { it.mine } == 1
        state.value = lists.copy(settings = ListSettings(list.id, list.name, list.shared, list.icon, last))
    }

    fun editSettings(
        name: String,
        shared: Boolean,
        icon: String?,
    ) {
        val lists = state.value as? Screen.Lists ?: return
        val settings = lists.settings ?: return
        state.value = lists.copy(settings = settings.copy(name = name, shared = shared, icon = icon))
    }

    // a new list is created and opens, a changed one shows on the home; a failure, e.g. a list of the
    // name exists, stays on the settings with the reason
    fun saveSettings() {
        val lists = state.value as? Screen.Lists ?: return
        val settings = lists.settings ?: return
        val name = settings.name.trim()
        if (name.isEmpty() || settings.busy) return
        state.value = lists.copy(settings = settings.copy(busy = true, error = null))
        settingsRequest(settings, if (settings.id == null) R.string.grocery_error_list_not_created else R.string.grocery_form_not_saved) { api ->
            if (settings.id == null) {
                val created = api.createList(name, settings.shared, settings.icon)
                (state.value as? Screen.Lists)?.let { state.value = it.copy(lists = it.lists + created, settings = null) }
                show(created.id)
            } else {
                api.updateList(settings.id, name, settings.shared, settings.icon)
                (state.value as? Screen.Lists)?.let { state.value = it.copy(settings = null) }
                load()
            }
        }
    }

    // the list of the settings goes with its items, after the user confirmed it (UX-4.1)
    fun deleteList() {
        val lists = state.value as? Screen.Lists ?: return
        val settings = lists.settings ?: return
        val id = settings.id ?: return
        if (settings.busy || settings.last) return
        state.value = lists.copy(settings = settings.copy(busy = true, error = null))
        settingsRequest(settings, R.string.grocery_form_not_deleted) { api ->
            api.deleteList(id)
            (state.value as? Screen.Lists)?.let { state.value = it.copy(lists = it.lists.filter { list -> list.id != id }, settings = null) }
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
            state.first { it is Screen.Lists }
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
        val lists = state.value as? Screen.Lists ?: return
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
        val lists = state.value as? Screen.Lists ?: return
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
        val lists = state.value as? Screen.Lists ?: return
        val current = lists.settings ?: settings
        state.value = lists.copy(settings = current.copy(busy = false, error = error))
    }

    // what was checked goes to the backend, the field is empty, the list or the home loads
    private fun show(open: Int?) {
        commitPending()
        val lists = state.value as? Screen.Lists ?: return
        state.value = lists.copy(open = open, groups = emptyList(), loading = true, error = null, itemName = "")
        scope.launch { store.update { it.copy(openList = open) } }
        load()
    }

    fun refresh() {
        val lists = state.value as? Screen.Lists ?: return
        state.value = lists.copy(loading = true, error = null)
        load()
    }

    fun typed(name: String) {
        val lists = state.value as? Screen.Lists ?: return
        state.value = lists.copy(itemName = name)
    }

    // the name typed goes to the list shown, the field is empty again and the items are loaded anew;
    // a failure keeps the name, to send it again
    fun addItem() {
        val lists = state.value as? Screen.Lists ?: return
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

    // the session goes, the deployment stays
    fun signOut() =
        scope.launch {
            commitPending()?.join()
            loading?.cancel()
            store.update { it.copy(token = null, email = null, expiresAt = null, pending = null, openList = null) }
            categories = null
            photos.clear()
            restartListening()
            showSignIn(store.load())
        }

    // the session of a sign-in; a login link that was pending is used up
    private suspend fun signedIn(
        token: String,
        email: String,
        expiresAt: String,
    ) {
        val until = runCatching { Instant.parse(expiresAt) }.getOrNull()
        store.update { it.copy(token = token, email = email, expiresAt = until, pending = null) }
        showLists(store.load())
    }

    private fun signedIn(stored: Stored) = stored.token != null && stored.expiresAt?.isAfter(clock.instant()) == true

    private suspend fun showSignIn(
        stored: Stored,
        error: UiText? = null,
    ) {
        val url = stored.apiUrl ?: return
        val deployment = stored.deployment ?: url
        state.value = Screen.SignIn(deployment, multiUser = null, busy = true, error = error)
        state.value =
            try {
                Screen.SignIn(deployment, multiUser = apiOf(url, null).info().userMode == "multi", error = error)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Screen.SignIn(deployment, multiUser = null, error = failure(e, R.string.error_deployment_unreachable))
            }
    }

    private fun showLists(stored: Stored) {
        state.value = Screen.Lists(stored.deployment ?: stored.apiUrl.orEmpty(), stored.email.orEmpty(), open = stored.openList)
        load()
        // the name an admin gave the user, shown instead of the address; without it the address stays
        scope.launch {
            val url = stored.apiUrl ?: return@launch
            val name = runCatching { apiOf(url, stored.token).whoAmI().name }.getOrNull() ?: return@launch
            (state.value as? Screen.Lists)?.let { state.value = it.copy(user = name) }
        }
        // the app of the version of the deployment, when it is newer
        scope.launch {
            val url = stored.apiUrl ?: return@launch
            val version = runCatching { newer(appVersion, apiOf(url, null).info().version) }.getOrNull() ?: return@launch
            (state.value as? Screen.Lists)?.let { state.value = it.copy(update = AppUpdate(version)) }
        }
    }

    // the check for updates of the menu: the version of the deployment asked for again; a newer one is
    // offered as on the start, else the home says why there is none
    fun checkUpdate() {
        val lists = state.value as? Screen.Lists ?: return
        if (lists.update != null) return
        state.value = lists.copy(updateNotice = uiText(R.string.update_checking))
        scope.launch {
            val notice =
                try {
                    val url = store.load().apiUrl ?: return@launch
                    val deployment = apiOf(url, null).info().version
                    val version = newer(appVersion, deployment)
                    when {
                        version != null -> {
                            (state.value as? Screen.Lists)?.let { state.value = it.copy(update = AppUpdate(version), updateNotice = null) }
                            return@launch
                        }
                        !isRelease(appVersion) -> uiText(R.string.update_app_no_release)
                        !isRelease(deployment) -> uiText(R.string.update_deployment_no_release)
                        else -> uiText(R.string.update_current, appVersion)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e, R.string.error_deployment_unreachable)
                }
            (state.value as? Screen.Lists)?.let { state.value = it.copy(updateNotice = notice) }
        }
    }

    fun dismissUpdateNotice() {
        (state.value as? Screen.Lists)?.let { state.value = it.copy(updateNotice = null) }
    }

    // the app of the version of the deployment is downloaded and handed to the installer of android,
    // which asks the user; the app ends when it is installed
    fun update() {
        val lists = state.value as? Screen.Lists ?: return
        val update = lists.update ?: return
        if (update.busy) return
        state.value = lists.copy(update = update.copy(busy = true, error = null))
        scope.launch {
            val error =
                try {
                    updater.install(update.version)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    uiText(R.string.error_update_not_downloaded)
                } catch (e: UpdateException) {
                    e.text
                } catch (e: Exception) {
                    uiText(R.string.error_update_not_installed)
                }
            (state.value as? Screen.Lists)?.let { state.value = it.copy(update = update.copy(busy = false, error = error)) }
        }
    }

    // the lists, then the items of the one open; a list that is gone leads to the home. A session that
    // ended leads to the sign-in
    private fun load() {
        loading?.cancel()
        loading =
            scope.launch {
                request(R.string.grocery_lists_not_loaded, { error -> lists { it.copy(loading = false, error = error) } }) { api ->
                    val lists = api.lists()
                    val wanted = (state.value as? Screen.Lists ?: return@request).open
                    val open = wanted?.takeIf { id -> lists.any { it.id == id } }
                    if (wanted != null && open == null) store.update { it.copy(openList = null) }
                    val groups = open?.let { groups(api.items(it), api) }.orEmpty()
                    lists { it.copy(lists = lists, open = open, groups = groups, loading = false) }
                }
            }
    }

    // a request of the signed in user with the api of their session; nothing without one, e.g. signed
    // out meanwhile. A session that ended leads to the sign-in, another failure goes to failed with
    // its text, or the fallback when it has none. The answer, null when there is none
    private suspend fun <T> request(
        @StringRes fallback: Int,
        failed: (UiText) -> Unit,
        call: suspend (GroceryApi) -> T,
    ): T? {
        val stored = store.load()
        val url = stored.apiUrl ?: return null
        val token = stored.token ?: return null
        return try {
            call(apiOf(url, token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 401) sessionEnded(e) else failed(e.text)
            null
        } catch (e: Exception) {
            failed(failure(e, fallback))
            null
        }
    }

    // changes the screen of the lists, nothing when another screen is shown
    private inline fun lists(change: (Screen.Lists) -> Screen.Lists) {
        (state.value as? Screen.Lists)?.let { state.value = change(it) }
    }

    // the session ended: what was checked is not sent, the sign-in says why; the stream of the old
    // session goes last, it may be the one that found the end
    private suspend fun sessionEnded(e: ApiException) {
        dropPending()
        categories = null
        photos.clear()
        store.update { it.copy(token = null, email = null, expiresAt = null, openList = null) }
        showSignIn(store.load(), error = e.text)
        restartListening()
    }

    private suspend fun groups(
        items: List<GroceryItemResponse>,
        api: GroceryApi,
    ): List<ItemGroup> {
        if (items.isEmpty()) return emptyList()
        val categories = categories ?: api.categories().also { categories = it }
        val order = categories.withIndex().associate { (index, category) -> category.name to index }
        val labels = categories.associate { it.name to it.label }
        return items
            .groupBy { it.category?.takeIf { category -> category in order } }
            .entries
            .sortedBy { (category, _) -> category?.let { order.getValue(it) } ?: Int.MAX_VALUE }
            .map { (category, grouped) -> ItemGroup(category, category?.let { UiText.Plain(labels.getValue(it)) } ?: NO_CATEGORY, grouped.sortedBy { it.id }) }
    }

    private fun failure(
        e: Exception,
        @StringRes fallback: Int,
    ): UiText =
        when (e) {
            is ApiException -> e.text
            is IOException -> uiText(R.string.error_connection)
            else -> uiText(fallback)
        }

    companion object {
        val NO_CATEGORY = uiText(R.string.grocery_list_no_category)

        // the waits before the stream of the changes is opened again
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 30_000L

        // how many photos are kept fetched
        const val MAX_PHOTOS = 60

        val NOT_ASKED = uiText(R.string.error_not_asked)
    }
}
