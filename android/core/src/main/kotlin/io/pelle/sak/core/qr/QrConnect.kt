package io.pelle.sak.core.qr

import io.pelle.sak.api.model.ExchangeResponse
import io.pelle.sak.core.ConnectCode
import io.pelle.sak.core.R
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.connectCode
import io.pelle.sak.core.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

// the connect by the QR code of the web app (SECURITY-3), the part of SakModel (SakModel.qr): the scan
// over the screen while it is open, the code confirmed by the user and exchanged at its deployment.
// links is the lock of the model for one login link or QR code at a time; switchOf is what the code
// replaces the session with (the SAK address the app has, who is signed in), after the start of the
// model; discover the url of the api of an address; exchange the session of a connect token at url;
// switch replaces the session of the model, false when it could not
class QrConnect(
    private val scope: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    private val allowUrl: Boolean,
    private val links: Mutex,
    private val switchOf: suspend (to: String) -> QrSwitch,
    private val discover: suspend (address: String) -> String,
    private val exchange: suspend (url: String, token: String) -> ExchangeResponse,
    private val switch: suspend (url: String, address: String, session: ExchangeResponse) -> Boolean,
) {
    private val scanning = MutableStateFlow<QrScan?>(null)

    // the scan of a QR code of the web app, over the screen while it is open; null when closed
    val scan: StateFlow<QrScan?> = scanning.asStateFlow()

    // the code that waits for the user to confirm the switch of the session
    private var confirming: ConnectCode? = null

    // the work of the code scanned, cancelled when the scan closes
    private var exchanging: Job? = null

    // the scan of a QR code opens; the ui asks for the camera now, not before
    fun open() {
        if (scanning.value == null) scanning.value = QrScan()
    }

    // the scan closes, what its code still asked of the deployment changes nothing
    fun close() {
        exchanging?.cancel()
        exchanging = null
        confirming = null
        scanning.value = null
    }

    // the camera was refused, or allowed in the settings of the app afterwards
    fun cameraDenied() = scanning.update { it?.copy(denied = true) }

    fun cameraAllowed() = scanning.update { it?.copy(denied = false) }

    // after a code that did not sign in: the next code is taken
    fun scanAgain() = scanning.update { if (it?.busy == false && it.confirm == null) it.copy(error = null) else it }

    // the text of a QR code the camera read: a code of the web app (connectCode) signs in to its
    // deployment once the user confirmed it, the address of the code shown: any host can make a code
    // (SECURITY-3), and a session there or elsewhere is replaced. One code at
    // a time: the camera reads the same code again and again, it is taken while nothing else runs and
    // no error waits for the user (scanAgain)
    fun scanned(text: String) {
        val current = scanning.value ?: QrScan()
        if (current.busy || current.confirm != null || current.error != null) return
        val code = connectCode(text, allowUrl)
        if (code == null) {
            scanning.value = current.copy(error = uiText(R.string.error_qr_no_sak))
            return
        }
        scanning.value = current.copy(busy = true)
        exchanging =
            scope.launch {
                val confirm = switchOf(code.address).copy(signsInAs = code.email)
                confirming = code
                scanning.update { it?.copy(busy = false, confirm = confirm) }
            }
    }

    // the user confirmed that the code replaces the session
    fun confirm() {
        val code = confirming ?: return
        confirming = null
        scanning.update { it?.copy(busy = true, confirm = null) }
        exchanging = scope.launch { exchangeCode(code) }
    }

    // the scan closes for the session the code brought, before its screen shows (switch)
    internal fun switched() {
        confirming = null
        scanning.value = null
    }

    // the code of a QR code exchanged at its deployment; the session it brings replaces the one before
    // (switch). A failure leaves the session as it was and says why
    private suspend fun exchangeCode(code: ConnectCode) =
        links.withLock {
            val unreachable = uiText(R.string.error_qr_unreachable, code.address)
            val url =
                try {
                    discover(code.address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withLock failed(unreachable)
                }
            val exchanged =
                try {
                    exchange(url, code.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    return@withLock failed(
                        when (e.status) {
                            401 -> uiText(R.string.error_qr_invalid)
                            429 -> uiText(R.string.error_too_many_attempts)
                            else -> e.text
                        },
                    )
                } catch (e: IOException) {
                    return@withLock failed(unreachable)
                } catch (e: Exception) {
                    return@withLock failed(uiText(R.string.error_qr_not_used))
                }
            // the code is used up: the switch is done also when the scan closes meanwhile
            withContext(NonCancellable) {
                if (!switch(url, code.address, exchanged)) failed(uiText(R.string.error_qr_not_used))
            }
        }

    private fun failed(text: UiText) {
        scanning.update { it?.copy(busy = false, error = text) }
    }
}
