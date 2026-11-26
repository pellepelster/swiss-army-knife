package io.pelle.sak.core

import androidx.annotation.StringRes
import io.pelle.sak.api.model.ExchangeResponse
import io.pelle.sak.api.model.InfoResponse
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.qr.QrConnect
import io.pelle.sak.core.qr.QrSwitch
import io.pelle.sak.core.session.PendingLogin
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.update.AppUpdate
import io.pelle.sak.core.update.UpdateOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import io.pelle.sak.core.settings.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
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

// a nonce of a login request: 32 random bytes, base64url without padding (43 characters)
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

    // a way to sign in, beside the QR code of the web app; EMAIL only in the multi user mode (UX-7)
    enum class SignInMethod { PASSWORD, EMAIL }

    data class SignIn(
        // the SAK address, else the url of the api
        val address: String,
        // the multi user mode, null until the deployment answered
        val multiUser: Boolean?,
        // the way to sign in the user chose, null while the three ways show: the QR code, the
        // password, a link by e-mail (UX-7)
        val method: SignInMethod? = null,
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

// the logic every app shares, without android: the deployment, the sign-in, the session and its
// requests (request), with the connect by a QR code (qr) and the update of the app (updates). An app
// shows its own screen once the user is signed in (showApp) and reacts to the session going (the
// hooks). apiOf makes the api of a url with a session token or none. Every change of the session (a
// sign-in, the sign-out, its end, another deployment) counts up the epoch; what a request of an older
// epoch answers changes nothing
abstract class SakModel<A : SakApi>(
    setup: SakSetup,
    protected val apiOf: (url: String, token: String?) -> A,
) {
    protected val store: SessionStore = setup.store
    protected val scope: CoroutineScope = setup.scope
    protected val clock: Clock = setup.clock
    private val allowUrl = setup.allowUrl
    private val lasting = setup.lasting
    private val nonce = setup.nonce

    protected val state = MutableStateFlow<Screen>(Screen.Starting)

    val screen: StateFlow<Screen> = state.asStateFlow()

    // the changes that wait for their undo, committed and dropped together (undoWindow)
    private val undos = mutableListOf<UndoWindow<*>>()

    // start(), which a link of a cold start waits for, so its screen does not come after the app's
    private var starting: Job? = null

    // one login link or QR code at a time
    private val links = Mutex()

    // counts up with every change of the session
    @Volatile
    private var epoch = 0

    // the connect by the QR code of the web app; the session it brings replaces the one before (switch)
    val qr =
        QrConnect(
            scope,
            allowUrl,
            links,
            switchOf = { to ->
                starting?.join()
                val stored = store.load()
                QrSwitch(stored.address ?: stored.apiUrl, to, if (signedIn(stored)) stored.email.orEmpty() else null)
            },
            discover = ::discover,
            exchange = { url, token -> apiOf(url, null).connectExchange(token) },
            switch = ::switch,
        )

    // the settings of the user signed in, in a dialog over the screen of the app
    val userSettings =
        UserSettings(
            scope,
            load = { failed -> request(R.string.error_settings_not_read, failed) { it.userSettings() } },
            save = { name, values, failed -> request(R.string.error_settings_not_saved, failed) { it.setUserSettings(name, values) } },
            changed = ::userSettingsChanged,
        )

    // the user changed a setting of theirs, e.g. the AI of the app turned on: what the app shows of it
    // is asked again
    protected open fun userSettingsChanged() {}

    // the update of the app to the version of the deployment, on the screen of the app (UX-13)
    val updates =
        UpdateOffer(
            scope,
            store,
            clock,
            setup.appVersion,
            setup.updater,
            deployment = { url -> apiOf(url, null).info().version },
            screen = { state.value as? AppScreen },
            show = { state.value = it },
        )

    // work that is done also when the screen, the activity or the model goes, e.g. a save the user
    // expects to happen
    protected fun launchLasting(block: suspend CoroutineScope.() -> Unit): Job = lasting.launch { withContext(NonCancellable) { block() } }

    // the screen of the app for a session, set as the state; its data loads after it
    protected abstract fun showApp(stored: Stored)

    // the user signs out: what is still to send is sent first
    protected open suspend fun signingOut() {}

    // the session ended: what is still to send is not sent
    protected open fun sessionEnding() {}

    // the session or the deployment went, also for the session of a QR code: what the app keeps of it
    // goes (e.g. photos fetched), a stream opened with the old token goes; last, after the new screen
    // shows (endSession)
    protected open fun sessionGone() {}

    // a change that waits for its undo of millis (UX-4.2): committed when the app goes to the
    // background and before the sign-out, dropped when the session ended
    protected fun <T : Any> undoWindow(
        millis: () -> Long,
        send: (T) -> Job,
    ): UndoWindow<T> = UndoWindow(scope, millis, send).also { undos += it }

    // the changes that wait for their undo are sent now; their requests
    protected fun commitUndos(): List<Job> = undos.mapNotNull { it.commit() }

    // the requests of the changes committed last, e.g. for a load to wait for
    protected fun committedUndos(): List<Job> = undos.mapNotNull { it.committed }

    // the app is seen, also on its first start; not again after a rotation (SakActivity)
    open fun foreground() {}

    // the app is no longer seen, not on a rotation: what waits for its undo is sent now, it would be
    // lost with the app
    open fun background() {
        commitUndos()
    }

    // the user leaves the session: what waits for its undo is sent first, then what the app still has
    private suspend fun leaving() {
        val committed = commitUndos()
        signingOut()
        committed.joinAll()
    }

    // where the app was left: the app while the session lasts, else the sign-in, else the deployment
    fun start() {
        starting =
            scope.launch {
                val stored = store.load()
                when {
                    stored.apiUrl == null -> state.value = Screen.Deployment()
                    signedIn(stored) -> startApp(stored)
                    // an expired session counts for nothing and is not kept (SEC-46)
                    stored.token != null -> {
                        store.update { it.withoutSession() }
                        showSignIn(store.load())
                    }
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
                val url = discover(address)
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

    // the url of the api of a SAK address, which names it (SERVER-6) and must answer as a SAK instance
    private suspend fun discover(address: String): String {
        val url =
            announcedApi(apiOf(address, null).wellKnown().api, allowUrl)
                ?: throw ApiException(0, uiText(R.string.error_no_sak_address))
        apiOf(url, null).info()
        return url
    }

    // the session of a QR code at url replaces the one before, whose app sends first what it still has
    // (as on the sign-out); false when it could not be stored, the session stays then
    private suspend fun switch(
        url: String,
        address: String,
        session: ExchangeResponse,
    ): Boolean {
        if (signedIn(store.load())) attempt { leaving() }
        return endSession(
            forget = { attempt { store.save(Stored(apiUrl = url, address = address)) } != null },
            show = {
                qr.switched()
                signedIn(url, session.sessionToken, session.email, session.expiresAt)
            },
        )
    }

    // the session goes, in one order for each of its ends (changeDeployment, signOut, sessionEnded, the
    // switch by a QR code): what it still answers changes nothing (the epoch), forget lets go of what is
    // kept of it (false when it could not: nothing more changes), the new screen shows (show), then the
    // hook of the app (sessionGone). The hook goes last: the change stream calls sessionEnded from its
    // job, which the restart of the stream in sessionGone cancels, so nothing that suspends may follow
    private suspend fun endSession(
        forget: suspend () -> Boolean,
        show: suspend () -> Unit,
    ): Boolean {
        epoch++
        if (!forget()) return false
        userSettings.close()
        show()
        sessionGone()
        return true
    }

    // back to the deployment, the session and a pending login go; what is still asked of the old
    // deployment changes nothing
    fun changeDeployment() =
        scope.launch {
            endSession(
                forget = {
                    // a store that fails to write is no reason to stay
                    attempt { store.save(Stored()) }
                    true
                },
                show = { state.value = Screen.Deployment() },
            )
        }

    // a link to the address, with a nonce of this request; also again to the same address
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

    // after a link went: another address is typed in
    fun otherAddress() {
        val signIn = state.value as? Screen.SignIn ?: return
        if (!signIn.busy) state.value = signIn.copy(linkSentTo = null, error = null)
    }

    // the deployment did not answer on the sign-in: asked again
    fun retrySignIn() {
        val signIn = state.value as? Screen.SignIn ?: return
        if (signIn.busy) return
        scope.launch { showSignIn(store.load()) }
    }

    // one of the ways to sign in, or back to all three (null); the e-mail login only in the multi user
    // mode, a single user deployment has none (UX-7)
    fun chooseMethod(method: Screen.SignInMethod?) {
        val signIn = state.value as? Screen.SignIn ?: return
        if (signIn.busy || (method == Screen.SignInMethod.EMAIL && signIn.multiUser != true)) return
        state.value = signIn.copy(method = method, error = null)
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
    // A link while signed in changes nothing, e.g. one tapped twice or given again by android
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
    // does not keep the user signed in
    fun signOut() =
        scope.launch {
            attempt { leaving() }
            endSession(
                forget = {
                    store.update { it.withoutSession().copy(pending = null) }
                    true
                },
                show = { showSignIn(store.load()) },
            )
        }

    // ends every session of the user on every device (SEC-4), then this one as a sign out does; failed
    // tells why it could not, the session stays then
    fun signOutEverywhere(failed: (UiText) -> Unit) =
        scope.launch {
            request(R.string.login_not_signed_out_everywhere, failed) { it.signOutEverywhere() } ?: return@launch
            signOut()
        }

    // a request of the signed in user with the api of their session; nothing without one, e.g. signed
    // out meanwhile. A session that ended leads to the sign-in, another failure goes to failed with
    // its text, or the fallback when it has none; the answer of a session that is no longer the one
    // stored changes nothing, also when it worked. The answer, null when there is none
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
            call(apiOf(url, token)).takeIf { since == epoch }
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

    // the session ended: the sign-in says why, what waits for its undo and what the app still has to
    // send are not sent. token is the one that was refused: a newer session stored meanwhile stays;
    // null ends any
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
                it.withoutSession()
            }
        }
        if (!ended) return
        endSession(
            forget = {
                undos.forEach { it.drop() }
                sessionEnding()
                true
            },
            show = { showSignIn(store.load(), error = e.text) },
        )
    }

    // the text of a failure: the one the backend sent, a lost connection, else the fallback
    protected fun failure(
        e: Exception,
        @StringRes fallback: Int,
    ): UiText = failureText(e, fallback)

    // the session of a sign-in at url; a login link that was pending is used up. Nothing when the
    // deployment changed meanwhile
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

    // the screen of the app once the start is done, null when it starts with another screen (the
    // deployment, the sign-in)
    protected suspend fun awaitApp(): AppScreen? = state.first { it !is Screen.Starting } as? AppScreen

    private fun signedIn(stored: Stored) = stored.token != null && stored.expiresAt?.isAfter(clock.instant()) == true

    // the screen of the app, the name of the user and the update the deployment offers (updates)
    private fun startApp(stored: Stored) {
        showApp(stored)
        // what is answered for another session than the one shown changes nothing
        val since = epoch
        // the name an admin gave the user, shown instead of the address; without it the address stays
        scope.launch {
            val url = stored.apiUrl ?: return@launch
            val name = attempt { apiOf(url, stored.token).whoAmI().name } ?: return@launch
            if (since == epoch) (state.value as? AppScreen)?.let { state.value = it.withUser(name) }
        }
        updates.started(stored) { since == epoch }
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
        // the minutes a login this app asked for is taken (PENDING_FOR)
        val NOT_ASKED = uiText(R.string.error_not_asked, PENDING_FOR.toMinutes())
    }
}

// the text of a failure: the one the backend sent, a lost connection, else the fallback
internal fun failureText(
    e: Exception,
    @StringRes fallback: Int,
): UiText =
    when (e) {
        is ApiException -> e.text
        is IOException -> uiText(R.string.error_connection)
        else -> uiText(fallback)
    }

// whether the deployment runs in the multi user mode (UX-7)
private fun InfoResponse.UserMode.multiUser() = this == InfoResponse.UserMode.MULTI
