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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

// how long a login link the app asked for is taken (the login token of the backend lives as long)
val PENDING_FOR: Duration = Duration.ofMinutes(15)

// the items of a category, in the order of the categories of the backend, the ones without one last
data class ItemGroup(
    val label: String,
    val items: List<GroceryItemResponse>,
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
        val selected: Int = 0,
        val groups: List<ItemGroup> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    ) : Screen
}

// the logic of the app, without android: the deployment, the sign-in by link or password and the
// items to buy of the lists, read only. apiOf makes the api of a url with a session token or none
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

    fun select(index: Int) {
        val lists = state.value as? Screen.Lists ?: return
        if (index !in lists.lists.indices || index == lists.selected) return
        state.value = lists.copy(selected = index, groups = emptyList(), loading = true, error = null)
        load()
    }

    fun refresh() {
        val lists = state.value as? Screen.Lists ?: return
        state.value = lists.copy(loading = true, error = null)
        load()
    }

    // the session goes, the deployment stays
    fun signOut() =
        scope.launch {
            val stored = store.load().copy(token = null, email = null, expiresAt = null, pending = null)
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
        state.value = Screen.Lists(stored.deployment ?: stored.apiUrl.orEmpty(), stored.email.orEmpty())
        load()
    }

    // the lists, then the items of the one shown; a session that ended leads to the sign-in
    private fun load() {
        loading?.cancel()
        loading =
            scope.launch {
                val stored = store.load()
                val url = stored.apiUrl ?: return@launch
                val api = apiOf(url, stored.token)
                try {
                    val lists = api.lists()
                    val current = state.value as? Screen.Lists ?: return@launch
                    val selected = current.selected.coerceIn(0, (lists.size - 1).coerceAtLeast(0))
                    val groups = lists.getOrNull(selected)?.let { groups(api.items(it.id), api) }.orEmpty()
                    state.value = current.copy(lists = lists, selected = selected, groups = groups, loading = false)
                } catch (e: ApiException) {
                    if (e.status == 401) {
                        val ended = stored.copy(token = null, email = null, expiresAt = null)
                        store.save(ended)
                        showSignIn(ended, error = e.message)
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
