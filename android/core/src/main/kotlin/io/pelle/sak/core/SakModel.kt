package io.pelle.sak.core

import androidx.annotation.StringRes
import io.pelle.sak.api.model.InfoResponse
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.session.PendingLogin
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.update.AppUpdate
import io.pelle.sak.core.update.UpdateException
import io.pelle.sak.core.update.Updater
import io.pelle.sak.core.update.isRelease
import io.pelle.sak.core.update.newer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

// how long a login link the app asked for is taken (the login token of the backend lives as long)
val PENDING_FOR: Duration = Duration.ofMinutes(15)

// a nonce of a login request: 32 random bytes, base64url without padding (43 characters, AN-8)
fun randomNonce(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

// what an app shows: until the user is signed in the screens of core, then the app's own, an
// AppScreen
interface Screen {
    data object Starting : Screen

    // the deployment, its SAK address typed in
    data class Deployment(
        val input: String = "",
        val busy: Boolean = false,
        val error: UiText? = null,
    ) : Screen

    // the sign-in follows the user mode (UX-7): in the multi user mode the e-mail login with a link to
    // the password login, in the single user mode only the password login
    data class SignIn(
        // the SAK address, else the url of the api
        val address: String,
        // the multi user mode, null until the deployment answered
        val multiUser: Boolean?,
        // the password login is shown: chosen, or the only one in the single user mode
        val withPassword: Boolean = multiUser == false,
        val busy: Boolean = false,
        val error: UiText? = null,
        // the address a link went to, the app waits for it
        val linkSentTo: String? = null,
    ) : Screen
}

// the screen of an app once the user is signed in: who is signed in, and the update of the app
interface AppScreen : Screen {
    // who is signed in: their name, else the e-mail address, or admin
    val user: String

    // the newer version of the app the deployment runs, offered on the home
    val update: AppUpdate?

    // the answer of the check for updates of the menu while there is no update to offer, e.g. that the
    // app is up-to-date; until the user dismisses it
    val updateNotice: UiText?

    fun withUser(user: String): AppScreen

    fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ): AppScreen
}

// the logic every app shares, without android: the deployment, the sign-in by link or password, the
// session that ends, the sign-out and the update of the app. An app shows its own screen once the user
// is signed in (showApp) and reacts to the session going (the hooks). apiOf makes the api of a url
// with a session token or none.
// Every change of the session (a sign-in, the sign-out, its end, another deployment) counts up the
// epoch; what a request of an older epoch answers changes nothing (AN-3)
abstract class SakModel<A : SakApi>(
    protected val store: SessionStore,
    protected val apiOf: (url: String, token: String?) -> A,
    protected val scope: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    private val allowUrl: Boolean,
    protected val clock: Clock = Clock.systemUTC(),
    // the version of the app, 1.4.2 of a release, latest of every other build
    private val appVersion: String = "latest",
    // none in the build of google play, which updates the app itself: the model offers no update
    private val updater: Updater? = Updater { error("no updater") },
    // the nonce of each login request
    private val nonce: () -> String = ::randomNonce,
) {
    protected val state = MutableStateFlow<Screen>(Screen.Starting)

    // the app updates itself to the version of the deployment, not in the build of google play (UX-13)
    val updates: Boolean get() = updater != null
    val screen: StateFlow<Screen> = state.asStateFlow()

    // where work goes that must not end with the screen, e.g. the last save when the app closes
    // (AN-29); the scope of the process in the app (SakProcess), the scope of the model in the tests
    var lasting: CoroutineScope = scope

    // start(), which a link of a cold start waits for, so its screen does not come after the app's
    private var starting: Job? = null

    // one login link at a time (AN-2)
    private val links = Mutex()

    // counts up with every change of the session (AN-3)
    @Volatile
    private var epoch = 0

    // work that is done also when the screen, the activity or the model goes (AN-29), e.g. a save the
    // user expects to happen; on the lasting scope, not cancelled with the screen
    protected fun launchLasting(block: suspend CoroutineScope.() -> Unit): Job = lasting.launch { withContext(NonCancellable) { block() } }

    // the screen of the app for a session, set as the state; its data loads after it
    protected abstract fun showApp(stored: Stored)

    // the user signs out: what is still to send is sent first
    protected open suspend fun signingOut() {}

    // the session ended: what is still to send is not sent
    protected open fun sessionEnding() {}

    // the session or the deployment went: what the app keeps of it goes (e.g. photos fetched)
    protected open fun clearSession() {}

    // the session or the deployment changed, e.g. a stream opened with the old token goes; after the
    // screen changed
    protected open fun sessionChanged() {}

    // where the app was left: the app while the session lasts, else the sign-in, else the deployment
    fun start() {
        starting =
            scope.launch {
                val stored = store.load()
                when {
                    stored.apiUrl == null -> state.value = Screen.Deployment()
                    signedIn(stored) -> startApp(stored)
                    else -> showSignIn(stored)
                }
            }
    }

    fun connect(input: String) {
        if ((state.value as? Screen.Deployment)?.busy == true) return
        val address = sakAddress(input, allowUrl)
        if (address == null) {
            state.value = Screen.Deployment(input, error = uiText(R.string.error_sak_address))
            return
        }
        val connecting = Screen.Deployment(input, busy = true)
        state.value = connecting
        val since = epoch
        scope.launch {
            try {
                // the SAK address names its api (SERVER-6), which must answer as a SAK instance
                val url =
                    announcedApi(apiOf(address, null).wellKnown().api, allowUrl)
                        ?: throw ApiException(0, uiText(R.string.error_no_sak_address))
                apiOf(url, null).info()
                if (since != epoch || state.value !== connecting) return@launch
                val stored = Stored(apiUrl = url, address = address)
                store.save(stored)
                showSignIn(stored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (since == epoch && state.value === connecting) {
                    state.value = Screen.Deployment(input, error = failure(e, R.string.error_no_deployment))
                }
            }
        }
    }

    // back to the deployment, the session and a pending login go; what is still asked of the old
    // deployment changes nothing
    fun changeDeployment() =
        scope.launch {
            epoch++
            // a store that fails to write is no reason to stay (AN-46)
            runCatchingNonCancel { store.save(Stored()) }
            clearSession()
            sessionChanged()
            state.value = Screen.Deployment()
        }

    // a link to the address, with a nonce of this request; again to the same address from the screen
    // that says it went (AN-42)
    fun requestLink(email: String) {
        val signIn = state.value as? Screen.SignIn ?: return
        if (signIn.busy) return
        val address = email.trim()
        if (address.isEmpty()) return
        val asking = signIn.copy(busy = true, error = null)
        state.value = asking
        val since = epoch
        scope.launch {
            val stored = store.load()
            val url = stored.apiUrl
            if (url == null) {
                if (state.value === asking) state.value = signIn.copy(busy = false)
                return@launch
            }
            val value = nonce()
            try {
                apiOf(url, null).requestLink(address, value)
                if (since != epoch) return@launch
                store.update { if (it.apiUrl == url) it.copy(pending = PendingLogin(url, clock.instant(), value)) else it }
                if (state.value === asking) state.value = signIn.copy(busy = false, linkSentTo = address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (since == epoch && state.value === asking) {
                    state.value = signIn.copy(busy = false, error = failure(e, R.string.error_link_not_requested))
                }
            }
        }
    }

    // after a link went: another address is typed in (AN-42)
    fun otherAddress() {
        val signIn = state.value as? Screen.SignIn ?: return
        if (!signIn.busy) state.value = signIn.copy(linkSentTo = null, error = null)
    }

    // the deployment did not answer on the sign-in: asked again (AN-42)
    fun retrySignIn() {
        val signIn = state.value as? Screen.SignIn ?: return
        if (signIn.busy) return
        scope.launch { showSignIn(store.load()) }
    }

    // between the e-mail login and the password login, in the multi user mode
    fun usePassword(withPassword: Boolean) {
        val signIn = state.value as? Screen.SignIn ?: return
        if (signIn.multiUser == true) state.value = signIn.copy(withPassword = withPassword, error = null)
    }

    fun signInWithPassword(password: String) {
        val signIn = state.value as? Screen.SignIn ?: return
        if (signIn.busy || password.isEmpty()) return
        val checking = signIn.copy(busy = true, error = null)
        state.value = checking
        val since = epoch
        scope.launch {
            val url = store.load().apiUrl
            if (url == null) {
                if (state.value === checking) state.value = signIn.copy(busy = false)
                return@launch
            }
            try {
                val session = apiOf(url, null).passwordLogin(password)
                if (since == epoch) signedIn(url, session.sessionToken, session.email, session.expiresAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (since == epoch && state.value === checking) {
                    state.value = signIn.copy(busy = false, error = failure(e, R.string.error_password_not_checked))
                }
            }
        }
    }

    // the token of a login link the signin page of the web app handed on; taken only for a login
    // this app asked for, on the same deployment, within PENDING_FOR, with the nonce of its request.
    // One link at a time; a link while signed in changes nothing, e.g. one tapped twice or given again
    // by android (AN-2)
    fun signInWithLink(token: String) =
        scope.launch {
            starting?.join()
            links.withLock {
                val stored = store.load()
                if (signedIn(stored)) return@withLock
                val pending = stored.pending
                val url = stored.apiUrl
                val fresh = pending != null && url == pending.apiUrl && Duration.between(pending.startedAt, clock.instant()) <= PENDING_FOR
                if (url == null || pending == null || !fresh) {
                    if (url == null) {
                        state.value = Screen.Deployment(error = NOT_ASKED)
                    } else {
                        showSignIn(stored, error = NOT_ASKED)
                    }
                    return@withLock
                }
                (state.value as? Screen.SignIn)?.let { state.value = it.copy(busy = true, error = null) }
                val since = epoch
                try {
                    val session = apiOf(url, null).exchange(token, pending.nonce)
                    if (since == epoch) signedIn(url, session.sessionToken, session.email, session.expiresAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the pending login stays: the right link may still come
                    if (since == epoch) showSignIn(store.load(), error = failure(e, R.string.error_link_not_used))
                }
            }
        }

    // the session goes, the deployment stays; what the app still sends goes first, a failure of it
    // does not keep the user signed in (AN-46)
    fun signOut() =
        scope.launch {
            runCatchingNonCancel { signingOut() }
            epoch++
            store.update { it.copy(token = null, email = null, expiresAt = null, pending = null, app = emptyMap()) }
            clearSession()
            sessionChanged()
            showSignIn(store.load())
        }

    // the check for updates of the menu: the version of the deployment asked for again; a newer one is
    // offered as on the start, else the app says why there is none
    fun checkUpdate() {
        if (!updates) return
        val app = state.value as? AppScreen ?: return
        if (app.update != null) return
        state.value = app.withUpdate(null, uiText(R.string.update_checking))
        scope.launch {
            val notice =
                try {
                    val url = store.load().apiUrl ?: return@launch
                    val deployment = apiOf(url, null).info().version
                    val version = newer(appVersion, deployment)
                    when {
                        version != null -> {
                            (state.value as? AppScreen)?.let { state.value = it.withUpdate(AppUpdate(version), null) }
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
            (state.value as? AppScreen)?.let { state.value = it.withUpdate(it.update, notice) }
        }
    }

    fun dismissUpdateNotice() {
        (state.value as? AppScreen)?.let { state.value = it.withUpdate(it.update, null) }
    }

    // the app of the version of the deployment is downloaded and handed to the installer of android,
    // which asks the user; the app ends when it is installed
    fun update() {
        val updater = updater ?: return
        val app = state.value as? AppScreen ?: return
        val update = app.update ?: return
        if (update.busy) return
        state.value = app.withUpdate(update.copy(busy = true, error = null), app.updateNotice)
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
            (state.value as? AppScreen)?.let { state.value = it.withUpdate(update.copy(busy = false, error = error), it.updateNotice) }
        }
    }

    // a request of the signed in user with the api of their session; nothing without one, e.g. signed
    // out meanwhile. A session that ended leads to the sign-in, another failure goes to failed with
    // its text, or the fallback when it has none; the answer of a session that is no longer the one
    // stored changes nothing (AN-3). The answer, null when there is none
    protected suspend fun <T> request(
        @StringRes fallback: Int,
        failed: (UiText) -> Unit,
        call: suspend (A) -> T,
    ): T? {
        val stored = store.load()
        val url = stored.apiUrl ?: return null
        val token = stored.token ?: return null
        val since = epoch
        return try {
            call(apiOf(url, token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when {
                e.status == 401 -> sessionEnded(e, token)
                since == epoch -> failed(e.text)
            }
            null
        } catch (e: Exception) {
            if (since == epoch) failed(failure(e, fallback))
            null
        }
    }

    // the session ended: the sign-in says why; what the app reacts with after the screen changed goes
    // last, e.g. the stream of the old session, which may be the one that found the end. token is the
    // one that was refused: a newer session stored meanwhile stays (AN-3); null ends any
    protected suspend fun sessionEnded(
        e: ApiException,
        token: String? = null,
    ) {
        var ended = false
        store.update {
            if (token != null && it.token != token) {
                it
            } else {
                ended = true
                it.copy(token = null, email = null, expiresAt = null, app = emptyMap())
            }
        }
        if (!ended) return
        epoch++
        sessionEnding()
        clearSession()
        showSignIn(store.load(), error = e.text)
        sessionChanged()
    }

    // the text of a failure: the one the backend sent, a lost connection, else the fallback
    protected fun failure(
        e: Exception,
        @StringRes fallback: Int,
    ): UiText =
        when (e) {
            is ApiException -> e.text
            is IOException -> uiText(R.string.error_connection)
            else -> uiText(fallback)
        }

    // the session of a sign-in at url; a login link that was pending is used up. Nothing when the
    // deployment changed meanwhile (AN-3)
    private suspend fun signedIn(
        url: String,
        token: String,
        email: String,
        expiresAt: String,
    ) {
        val until = runCatching { Instant.parse(expiresAt) }.getOrNull()
        var taken = false
        store.update {
            if (it.apiUrl != url) {
                it
            } else {
                taken = true
                it.copy(token = token, email = email, expiresAt = until, pending = null)
            }
        }
        if (!taken) return
        epoch++
        startApp(store.load())
    }

    // a failure of a hook or the store is not the end of what called it (AN-46)
    private suspend fun runCatchingNonCancel(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun signedIn(stored: Stored) = stored.token != null && stored.expiresAt?.isAfter(clock.instant()) == true

    // the screen of the app, the name of the user and the update the deployment offers
    private fun startApp(stored: Stored) {
        showApp(stored)
        // the name an admin gave the user, shown instead of the address; without it the address stays
        scope.launch {
            val url = stored.apiUrl ?: return@launch
            val name = runCatching { apiOf(url, stored.token).whoAmI().name }.getOrNull() ?: return@launch
            (state.value as? AppScreen)?.let { state.value = it.withUser(name) }
        }
        // the app of the version of the deployment, when it is newer
        if (updates) scope.launch {
            val url = stored.apiUrl ?: return@launch
            val version = runCatching { newer(appVersion, apiOf(url, null).info().version) }.getOrNull() ?: return@launch
            (state.value as? AppScreen)?.let { state.value = it.withUpdate(AppUpdate(version), it.updateNotice) }
        }
    }

    protected suspend fun showSignIn(
        stored: Stored,
        error: UiText? = null,
    ) {
        val url = stored.apiUrl ?: return
        val address = stored.address ?: url
        val asking = Screen.SignIn(address, multiUser = null, busy = true, error = error)
        state.value = asking
        val shown =
            try {
                Screen.SignIn(address, multiUser = apiOf(url, null).info().userMode.multiUser(), error = error)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Screen.SignIn(address, multiUser = null, error = failure(e, R.string.error_deployment_unreachable))
            }
        // only while nothing else came meanwhile, e.g. the app of a sign-in
        if (state.value === asking) state.value = shown
    }

    companion object {
        val NOT_ASKED = uiText(R.string.error_not_asked)
    }
}

// whether the deployment runs in the multi user mode (UX-7)
private fun InfoResponse.UserMode.multiUser() = this == InfoResponse.UserMode.MULTI
