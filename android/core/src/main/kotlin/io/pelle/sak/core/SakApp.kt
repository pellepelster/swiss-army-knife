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
import io.pelle.sak.core.ui.SakTheme
import io.pelle.sak.core.update.AndroidUpdater
import io.pelle.sak.core.update.PendingInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// what lives as long as the process of an app: one engine and one client for every api, and the
// scope of work that must not end with the screen (SakModel.launchLasting); never closed, the
// process ends them (AN-29)
object SakProcess {
    val engine: HttpClientEngine by lazy { OkHttp.create() }
    val client: HttpClient by lazy { sakClient(engine) }
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}

// the model of an app for its activity, kept across a rotation: the store of the session of the app,
// its updater and the languages of the phone for its api; app is the name of the app, e.g. grocery
// (sak-grocery-<version>.apk, the key sak-grocery-session). The app makes its model with them
abstract class SakViewModel<M : SakModel<*>>(
    application: Application,
    app: String,
) : AndroidViewModel(application) {
    protected val client: HttpClient get() = SakProcess.client
    protected val store = DataStoreSessionStore(application, app)
    protected val updater = AndroidUpdater(application, SakProcess.engine, app)

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
// for while the app was not seen shows when it is seen again (AN-12)
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

    override fun onStart() {
        super.onStart()
        PendingInstall.seen(this)
    }

    override fun onStop() {
        PendingInstall.unseen()
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
