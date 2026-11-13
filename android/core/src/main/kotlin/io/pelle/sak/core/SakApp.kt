package io.pelle.sak.core

import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
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
import io.pelle.sak.core.qr.CameraXQrCamera
import io.pelle.sak.core.qr.QrCamera
import io.pelle.sak.core.ui.SakContent
import io.pelle.sak.core.ui.SakTheme
import io.pelle.sak.core.update.AndroidUpdater
import io.pelle.sak.core.update.PendingInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// what lives as long as the process: one engine and one client for every api, and the scope of work
// that must not end with the screen (SakModel.launchLasting); never closed, the process ends them. The
// tests set a fake engine and store before an activity starts
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

    // the camera of the scan of a QR code, a fake in the tests
    var qrCamera: QrCamera = CameraXQrCamera

    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}

// the model of an app for its activity, kept across a rotation; the app makes its model with the store,
// the updater and the languages. app is the name of the app, e.g. grocery (sak-grocery-<version>.apk,
// the key sak-grocery-session). selfUpdate is BuildConfig.SELF_UPDATE: false in the build of google
// play, which has no updater
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

// the one activity of an app: its content after the shared sign-in (SakContent), and the login links
// the signin page of the web app hands on (<scheme>://signin?token=…). A link is taken once: not again
// after a rotation, and not when android starts the activity again from the recent apps with the intent
// it had (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY). The confirmation of an update the installer asked for
// while the app was not seen shows when it is seen again. foreground() and background() do not run on
// a rotation or another change of the configuration: the activity is recreated, the model stays
abstract class SakActivity : ComponentActivity() {
    // the scheme of the login link, the package of the release, e.g. io.pelle.sak.grocery; the debug
    // build has a package of its own (.debug) and the same scheme
    protected abstract val signinScheme: String

    protected abstract val sakModel: SakModel<*>

    // the name of the app, on the first screen of the sign-in (SakContent)
    @get:StringRes
    protected abstract val appName: Int

    @Composable
    protected abstract fun Content()

    // an intent of the app other than a login link, e.g. photos another app shares; called once for an
    // intent, as a login link. True when it was taken: the activity keeps it no longer
    protected open fun receive(intent: Intent): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) take(intent)
        setContent { SakTheme { SakContent(sakModel, stringResource(appName)) { Content() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    // the app is seen again, e.g. it hears the changes of its lists (LIST-4)
    protected open fun foreground() {}

    // the app is no longer seen, e.g. what waits to be sent goes now, it would be lost with the app
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
