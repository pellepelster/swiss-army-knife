package io.pelle.sak.core.update

import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.R
import io.pelle.sak.core.attempt
import io.pelle.sak.core.failureText
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Duration

// the update of the app to the version of the deployment (UX-13), the part of SakModel (SakModel.updates):
// offered on the screen of the app (AppScreen.update), asked for by the app itself at most once in
// UPDATE_EVERY and by the check of the menu. None without an updater, the build of google play, which
// updates the app itself. deployment is the version the deployment at url runs; screen is the screen of
// the app shown, null while another shows, show replaces it
class UpdateOffer(
    private val scope: CoroutineScope,
    private val store: SessionStore,
    private val clock: Clock,
    // the version of the app, 1.4.2 of a release, latest of every other build
    private val appVersion: String,
    private val updater: Updater?,
    private val deployment: suspend (url: String) -> String?,
    private val screen: () -> AppScreen?,
    private val show: (AppScreen) -> Unit,
) {
    // the app updates itself to the version of the deployment, not in the build of google play (UX-13)
    val enabled: Boolean get() = updater != null

    // the app of a session started (stored): it asks by itself at most once in UPDATE_EVERY (UX-13), a
    // check that failed is no check. current is false once the session is no longer the one shown, what
    // is answered then changes nothing
    fun started(
        stored: Stored,
        current: () -> Boolean,
    ) {
        if (!enabled) return
        scope.launch {
            val url = stored.apiUrl ?: return@launch
            val checked = stored.updateCheckedAt
            if (checked != null && checked.plus(UPDATE_EVERY).isAfter(clock.instant())) return@launch
            val deployment = attempt { deployment(url) } ?: return@launch
            checked(url)
            val version = attempt { newer(appVersion, deployment) } ?: return@launch
            if (current()) screen()?.let { show(it.withUpdate(AppUpdate(version), it.updateNotice)) }
        }
    }

    // the check for updates of the menu: the version of the deployment asked for again; a newer one is
    // offered as on the start, else the app says why there is none
    fun check() {
        if (!enabled) return
        val app = screen() ?: return
        if (app.update != null) return
        show(app.withUpdate(null, uiText(R.string.update_checking)))
        scope.launch {
            val notice =
                try {
                    val url = store.load().apiUrl ?: return@launch
                    val deployment = deployment(url)
                    checked(url)
                    val version = newer(appVersion, deployment)
                    when {
                        version != null -> {
                            screen()?.let { show(it.withUpdate(AppUpdate(version), null)) }
                            return@launch
                        }
                        !isRelease(appVersion) -> uiText(R.string.update_app_no_release)
                        !isRelease(deployment) -> uiText(R.string.update_deployment_no_release)
                        else -> uiText(R.string.update_current, appVersion)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failureText(e, R.string.error_deployment_unreachable)
                }
            screen()?.let { show(it.withUpdate(it.update, notice)) }
        }
    }

    fun dismissNotice() {
        screen()?.let { show(it.withUpdate(it.update, null)) }
    }

    // Later on the update: it is offered again by the next check, by itself or of the menu (UX-13)
    fun later() {
        val app = screen() ?: return
        if (app.update?.busy == true) return
        show(app.withUpdate(null, app.updateNotice))
    }

    // the app of the version of the deployment is downloaded and handed to the installer of android,
    // which asks the user; the app ends when it is installed
    fun install() {
        val updater = updater ?: return
        val app = screen() ?: return
        val update = app.update ?: return
        if (update.busy) return
        show(app.withUpdate(update.copy(busy = true, error = null), app.updateNotice))
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
            screen()?.let { show(it.withUpdate(update.copy(busy = false, error = error), it.updateNotice)) }
        }
    }

    // the version of the deployment at url is known now; nothing when the deployment changed meanwhile
    private suspend fun checked(url: String) =
        attempt { store.update { if (it.apiUrl == url) it.copy(updateCheckedAt = clock.instant()) else it } }

    companion object {
        // how often the app asks the deployment for its version by itself (UX-13)
        val UPDATE_EVERY: Duration = Duration.ofDays(7)
    }
}
