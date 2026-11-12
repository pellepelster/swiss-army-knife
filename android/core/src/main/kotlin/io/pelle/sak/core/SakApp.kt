package io.pelle.sak.core

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.AndroidViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.pelle.sak.core.api.sakClient
import io.pelle.sak.core.session.DataStoreSessionStore
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.ui.SakTheme
import io.pelle.sak.core.update.AndroidUpdater
import io.pelle.sak.core.update.PendingInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// what lives as long as the process of an app: one engine and one client for every api, and the
// scope of work that must not end with the screen (SakModel.launchLasting); never closed, the
// process ends them (AN-29). The tests of an app put the engine of a fake backend and a store in
// memory in their place before an activity starts (engine, sessionStore)
object SakProcess {
    private var engineOf: HttpClientEngine? = null
    private var clientOf: HttpClient? = null

    var engine: HttpClientEngine
        @Synchronized get() = engineOf ?: OkHttp.create().also { engineOf = it }

        @Synchronized set(value) {
            engineOf = value
            clientOf = null
        }

    val client: HttpClient
        @Synchronized get() = clientOf ?: sakClient(engine).also { clientOf = it }

    // the store of the session of an app by its name, encrypted with a key of the android keystore
    var sessionStore: (Application, String) -> SessionStore = ::DataStoreSessionStore

    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}

// the model of an app for its activity, kept across a rotation: the store of the session of the app,
// its updater and the languages of the phone for its api; app is the name of the app, e.g. grocery
// (sak-grocery-<version>.apk, the key sak-grocery-session). The app makes its model with them.
// selfUpdate is BuildConfig.SELF_UPDATE of the app: false in the build of google play, which has no
// updater
abstract class SakViewModel<M : SakModel<*>>(
    application: Application,
    app: String,
    selfUpdate: Boolean,
) : AndroidViewModel(application) {
    protected val client: HttpClient get() = SakProcess.client
    protected val store = SakProcess.sessionStore(application, app)
    protected val updater = if (selfUpdate) AndroidUpdater(application, SakProcess.engine, app) else null

    // the languages of the phone, e.g. de-DE,en-US, for the texts the backend answers
    protected val languages: String get() = LocaleList.getDefault().toLanguageTags()

    abstract val model: M

    // the model started, its lasting work on the scope of the process
    protected fun M.started(): M = also {
        it.lasting = SakProcess.scope
        it.start()
    }
}

// the one activity of an app: its compose content in the theme of SAK, and the login links the signin
// page of the web app hands on (<scheme>://signin?token=…). A link is taken once: not again after a
// rotation, and not when android starts the activity again from the recent apps with the intent it
// had (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, AN-2). The confirmation of an update the installer asked
// for while the app was not seen shows when it is seen again (AN-12). The app comes to the foreground
// and goes to the background with foreground() and background(), not with a rotation or another
// change of the configuration: the activity is made anew then, the model and what it waits for stay
// (e.g. an undo that runs)
abstract class SakActivity : ComponentActivity() {
    // the scheme of the login link, the package of the release, e.g. io.pelle.sak.grocery; the debug
    // build has a package of its own (.debug) and the same scheme
    protected abstract val signinScheme: String

    protected abstract val sakModel: SakModel<*>

    @Composable
    protected abstract fun Content()

    // an intent of the app other than a login link, e.g. photos another app shares; called once for an
    // intent, as a login link. True when it was taken: the activity keeps it no longer
    protected open fun receive(intent: Intent): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) take(intent)
        setContent { SakTheme { Content() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    // the app is seen again, e.g. it hears the changes of its lists (LIST-4)
    protected open fun foreground() {}

    // the app is no longer seen, e.g. what waits to be sent goes now, it would be lost with the app
    // (AN-29)
    protected open fun background() {}

    override fun onStart() {
        super.onStart()
        PendingInstall.seen(this)
        // the activity of a rotation: the app stayed in the foreground
        if (sakModel.configurationChanging) sakModel.configurationChanging = false else foreground()
    }

    override fun onStop() {
        PendingInstall.unseen()
        if (isChangingConfigurations) sakModel.configurationChanging = true else background()
        super.onStop()
    }

    private fun take(intent: Intent?) {
        intent ?: return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // what it had is taken once, a recreated activity must not find it again
        if (receive(intent)) {
            setIntent(Intent(this, javaClass))
            return
        }
        signIn(intent)
    }

    private fun signIn(intent: Intent) {
        val link = intent.data ?: return
        if (link.scheme != signinScheme || link.host != "signin") return
        link.getQueryParameter("token")?.takeIf { it.isNotEmpty() }?.let { sakModel.signInWithLink(it) }
        // the token is used once, a recreated activity must not find it again
        setIntent(Intent(intent).setData(null))
    }
}
