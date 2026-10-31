package io.pelle.sak.grocery

import io.pelle.sak.grocery.api.ApiException
import io.pelle.sak.grocery.api.GroceryApi
import io.pelle.sak.grocery.api.GroceryItemResponse
import io.pelle.sak.grocery.api.GroceryListResponse
import io.pelle.sak.grocery.session.PendingLogin
import io.pelle.sak.grocery.session.SessionStore
import io.pelle.sak.grocery.session.Stored
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val label: String,
    val items: List<GroceryItemResponse>,
)

// the check that can still be undone; serial starts the bar of the time left anew for every check
data class Undo(
    val text: String,
    val serial: Int,
)

// the list the user is creating: its name and whether all users see it
data class NewList(
    val name: String = "",
    val shared: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

// what the app shows
sealed interface Screen {
    data object Starting : Screen

    // the deployment, its base domain typed in
    data class Deployment(
        val input: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) : Screen

    data class SignIn(
        val deployment: String,
        val email: Boolean,
        val password: Boolean,
        val busy: Boolean = false,
        val error: String? = null,
        // the address a link went to, the app waits for it
        val linkSentTo: String? = null,
    ) : Screen

    data class Lists(
        val deployment: String,
        // who is signed in, the e-mail address or admin
        val user: String,
        val lists: List<GroceryListResponse> = emptyList(),
        // the id of the list open, null on the home, which shows all lists
        val open: Int? = null,
        val groups: List<ItemGroup> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        // the name typed into the field at the top, kept while it is sent (GROCERY-2)
        val itemName: String = "",
        val adding: Boolean = false,
        // the items checked while they can be undone, struck through until they go
        val struck: Set<Int> = emptySet(),
        val undo: Undo? = null,
        // the screen of a new list, over the home
        val newList: NewList? = null,
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
) {
    private val state = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = state.asStateFlow()

    private var loading: Job? = null

    // the check that can still be undone: its item and the timer that sends it to the backend
    private var pending: Pair<GroceryItemResponse, Job>? = null
    private var serial = 0

    // start(), which a link of a cold start waits for, so its screen does not come after the lists
    private var starting: Job? = null

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
            state.value = Screen.Deployment(input, error = "Enter the base domain of your SAK deployment, e.g. example.org.")
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
                state.value = Screen.Deployment(input, error = failure(e, "No SAK deployment answers at this domain."))
            }
        }
    }

    // back to the deployment, the session and a pending login go
    fun changeDeployment() =
        scope.launch {
            store.save(Stored())
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
                store.save(stored.copy(pending = PendingLogin(url, clock.instant())))
                state.value = signIn.copy(busy = false, linkSentTo = address)
            } catch (e: Exception) {
                state.value = signIn.copy(busy = false, error = failure(e, "The login link could not be requested."))
            }
        }
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
                signedIn(stored, session.sessionToken, session.email, session.expiresAt)
            } catch (e: Exception) {
                state.value = signIn.copy(busy = false, error = failure(e, "The password could not be checked."))
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
                signedIn(stored.copy(pending = null), session.sessionToken, session.email, session.expiresAt)
            } catch (e: Exception) {
                showSignIn(stored, error = failure(e, "The login link could not be used."))
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
            state.value = it.copy(struck = it.struck + item.id, undo = Undo("${item.name} bought", ++serial), error = null)
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
        return scope.launch {
            val stored = store.load()
            val url = stored.apiUrl ?: return@launch
            try {
                apiOf(url, stored.token).checkItem(item.id)
            } catch (e: ApiException) {
                if (e.status == 401) {
                    sessionEnded(stored, e)
                } else {
                    failed(e.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(failure(e, "The item could not be checked."))
            }
        }
    }

    private fun failed(error: String?) {
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

    // from a list or the new list back to the home, the next start shows the home
    fun back() {
        val lists = state.value as? Screen.Lists ?: return
        when {
            lists.newList != null -> state.value = lists.copy(newList = null)
            lists.open != null -> show(null)
        }
    }

    // the screen of a new list, from the home
    fun newList() {
        val lists = state.value as? Screen.Lists ?: return
        if (lists.open == null) state.value = lists.copy(newList = NewList())
    }

    fun editNewList(
        name: String,
        shared: Boolean,
    ) {
        val lists = state.value as? Screen.Lists ?: return
        val newList = lists.newList ?: return
        state.value = lists.copy(newList = newList.copy(name = name, shared = shared))
    }

    // the new list is created and opens; a failure, e.g. a list of the name exists, stays on the
    // screen of the new list with the reason
    fun createList() {
        val lists = state.value as? Screen.Lists ?: return
        val newList = lists.newList ?: return
        val name = newList.name.trim()
        if (name.isEmpty() || newList.busy) return
        state.value = lists.copy(newList = newList.copy(busy = true, error = null))
        scope.launch {
            val stored = store.load()
            val url = stored.apiUrl ?: return@launch
            try {
                val created = apiOf(url, stored.token).createList(name, newList.shared)
                (state.value as? Screen.Lists)?.let { state.value = it.copy(lists = it.lists + created, newList = null) }
                show(created.id)
            } catch (e: ApiException) {
                if (e.status == 401) sessionEnded(stored, e) else newListFailed(e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                newListFailed(failure(e, "The list could not be created."))
            }
        }
    }

    private fun newListFailed(error: String?) {
        val lists = state.value as? Screen.Lists ?: return
        val newList = lists.newList ?: return
        state.value = lists.copy(newList = newList.copy(busy = false, error = error))
    }

    // what was checked goes to the backend, the field is empty, the list or the home loads
    private fun show(open: Int?) {
        commitPending()
        val lists = state.value as? Screen.Lists ?: return
        state.value = lists.copy(open = open, groups = emptyList(), loading = true, error = null, itemName = "")
        scope.launch { store.save(store.load().copy(openList = open)) }
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
            val stored = store.load()
            val url = stored.apiUrl ?: return@launch
            try {
                apiOf(url, stored.token).addItem(list, name)
                (state.value as? Screen.Lists)?.let { state.value = it.copy(itemName = "", adding = false) }
                load()
            } catch (e: ApiException) {
                if (e.status == 401) {
                    sessionEnded(stored, e)
                } else {
                    (state.value as? Screen.Lists)?.let { state.value = it.copy(adding = false, error = e.message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (state.value as? Screen.Lists)?.let {
                    state.value = it.copy(adding = false, error = failure(e, "The item could not be added."))
                }
            }
        }
    }

    // the session goes, the deployment stays
    fun signOut() =
        scope.launch {
            commitPending()?.join()
            val stored = store.load().copy(token = null, email = null, expiresAt = null, pending = null, openList = null)
            store.save(stored)
            showSignIn(stored)
        }

    private suspend fun signedIn(
        stored: Stored,
        token: String,
        email: String,
        expiresAt: String,
    ) {
        val session = stored.copy(token = token, email = email, expiresAt = runCatching { Instant.parse(expiresAt) }.getOrNull())
        store.save(session)
        showLists(session)
    }

    private fun signedIn(stored: Stored) = stored.token != null && stored.expiresAt?.isAfter(clock.instant()) == true

    private suspend fun showSignIn(
        stored: Stored,
        error: String? = null,
    ) {
        val url = stored.apiUrl ?: return
        val deployment = stored.deployment ?: url
        state.value = Screen.SignIn(deployment, email = false, password = false, busy = true, error = error)
        state.value =
            try {
                val methods = apiOf(url, null).loginMethods()
                Screen.SignIn(deployment, methods.email, methods.password, error = error)
            } catch (e: Exception) {
                Screen.SignIn(deployment, email = false, password = false, error = failure(e, "The deployment could not be reached."))
            }
    }

    private fun showLists(stored: Stored) {
        state.value = Screen.Lists(stored.deployment ?: stored.apiUrl.orEmpty(), stored.email.orEmpty(), open = stored.openList)
        load()
    }

    // the lists, then the items of the one open; a list that is gone leads to the home. A session that
    // ended leads to the sign-in
    private fun load() {
        loading?.cancel()
        loading =
            scope.launch {
                val stored = store.load()
                val url = stored.apiUrl ?: return@launch
                val api = apiOf(url, stored.token)
                try {
                    val lists = api.lists()
                    val wanted = (state.value as? Screen.Lists ?: return@launch).open
                    val open = wanted?.takeIf { id -> lists.any { it.id == id } }
                    if (wanted != null && open == null) store.save(store.load().copy(openList = null))
                    val groups = open?.let { groups(api.items(it), api) }.orEmpty()
                    (state.value as? Screen.Lists)?.let { state.value = it.copy(lists = lists, open = open, groups = groups, loading = false) }
                } catch (e: ApiException) {
                    if (e.status == 401) {
                        sessionEnded(stored, e)
                    } else {
                        (state.value as? Screen.Lists)?.let { state.value = it.copy(loading = false, error = e.message) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    (state.value as? Screen.Lists)?.let {
                        state.value = it.copy(loading = false, error = failure(e, "The lists could not be loaded."))
                    }
                }
            }
    }

    private suspend fun sessionEnded(
        stored: Stored,
        e: ApiException,
    ) {
        val ended = stored.copy(token = null, email = null, expiresAt = null, openList = null)
        store.save(ended)
        showSignIn(ended, error = e.message)
    }

    private suspend fun groups(
        items: List<GroceryItemResponse>,
        api: GroceryApi,
    ): List<ItemGroup> {
        if (items.isEmpty()) return emptyList()
        val categories = api.categories()
        val order = categories.withIndex().associate { (index, category) -> category.name to index }
        val labels = categories.associate { it.name to it.label }
        return items
            .groupBy { it.category?.takeIf { category -> category in order } }
            .entries
            .sortedBy { (category, _) -> category?.let { order.getValue(it) } ?: Int.MAX_VALUE }
            .map { (category, grouped) -> ItemGroup(category?.let { labels.getValue(it) } ?: NO_CATEGORY, grouped.sortedBy { it.id }) }
    }

    private fun failure(
        e: Exception,
        fallback: String,
    ) = when (e) {
        is ApiException -> e.message ?: fallback
        is IOException -> "The deployment could not be reached, check the connection."
        else -> fallback
    }

    companion object {
        const val NO_CATEGORY = "No category"

        const val NOT_ASKED = "This login link was not requested in this app, or it is older than 15 minutes. Request a new one."
    }
}
