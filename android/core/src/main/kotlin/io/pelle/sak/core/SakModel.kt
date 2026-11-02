package io.pelle.sak.core

import androidx.annotation.StringRes
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

// how long a login link the app asked for is taken (the login token of the backend lives as long)
val PENDING_FOR: Duration = Duration.ofMinutes(15)

// what an app shows: until the user is signed in the screens of core, then the app's own, an
// AppScreen
interface Screen {
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
}

// the screen of an app once the user is signed in: who is signed in, and the update of the app
interface AppScreen : Screen {
    val deployment: String

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
// with a session token or none
abstract class SakModel<A : SakApi>(
    protected val store: SessionStore,
    protected val apiOf: (url: String, token: String?) -> A,
    protected val scope: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    private val allowUrl: Boolean,
    protected val clock: Clock = Clock.systemUTC(),
    // the version of the app, 1.4.2 of a release, latest of every other build
    private val appVersion: String = "latest",
    private val updater: Updater = Updater { error("no updater") },
) {
    protected val state = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = state.asStateFlow()

    // start(), which a link of a cold start waits for, so its screen does not come after the app's
    private var starting: Job? = null

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
            clearSession()
            sessionChanged()
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

    // the session goes, the deployment stays
    fun signOut() =
        scope.launch {
            signingOut()
            store.update { it.copy(token = null, email = null, expiresAt = null, pending = null, openList = null) }
            clearSession()
            sessionChanged()
            showSignIn(store.load())
        }

    // the check for updates of the menu: the version of the deployment asked for again; a newer one is
    // offered as on the start, else the app says why there is none
    fun checkUpdate() {
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
    // its text, or the fallback when it has none. The answer, null when there is none
    protected suspend fun <T> request(
        @StringRes fallback: Int,
        failed: (UiText) -> Unit,
        call: suspend (A) -> T,
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

    // the session ended: the sign-in says why; what the app reacts with after the screen changed goes
    // last, e.g. the stream of the old session, which may be the one that found the end
    protected suspend fun sessionEnded(e: ApiException) {
        sessionEnding()
        clearSession()
        store.update { it.copy(token = null, email = null, expiresAt = null, openList = null) }
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

    // the session of a sign-in; a login link that was pending is used up
    private suspend fun signedIn(
        token: String,
        email: String,
        expiresAt: String,
    ) {
        val until = runCatching { Instant.parse(expiresAt) }.getOrNull()
        store.update { it.copy(token = token, email = email, expiresAt = until, pending = null) }
        startApp(store.load())
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
        scope.launch {
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

    companion object {
        val NOT_ASKED = uiText(R.string.error_not_asked)
    }
}
