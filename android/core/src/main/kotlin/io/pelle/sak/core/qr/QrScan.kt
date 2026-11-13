package io.pelle.sak.core.qr

import io.pelle.sak.core.UiText

// the scan of the QR code of the web app that connects the app (SECURITY-3), over the screen of the
// model while it is open (SakModel.scan)
data class QrScan(
    // the code is exchanged for a session
    val busy: Boolean = false,
    // why the code scanned last did not sign in; the scan waits until the user scans again
    val error: UiText? = null,
    // the camera was refused: the scan says so and leads to the settings of the app (PRIVACY-7)
    val denied: Boolean = false,
    // signed in already: asked before the code replaces the session
    val confirm: QrSwitch? = null,
)

// the session of from replaced by one of to, the SAK addresses; the same deployment signs in again,
// as the user who shows the code
data class QrSwitch(
    val from: String,
    val to: String,
    // who is signed in now
    val user: String,
) {
    val same: Boolean get() = from == to
}
