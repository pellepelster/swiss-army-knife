package io.pelle.sak.core.qr

import io.pelle.sak.core.UiText

// the scan of the QR code of the web app that connects the app (SECURITY-3), over the screen of the
// model while it is open (SakModel.scan)
data class QrScan(
    // the code is exchanged for a session
    val busy: Boolean = false,
    // why the code scanned last did not sign in; the scan waits until the user scans again
    val error: UiText? = null,
    // the camera was refused: the scan says so and leads to the settings of the app
    val denied: Boolean = false,
    // asked for every code before it is exchanged: the SAK instance it signs in to (SECURITY-3)
    val confirm: QrSwitch? = null,
)

// the code signs in to `to`, a SAK address; `from` the one the app has, none before the first sign-in.
// The same deployment signs in again, as the user who shows the code
data class QrSwitch(
    val from: String?,
    val to: String,
    // who is signed in now, none while signed out
    val user: String?,
) {
    val same: Boolean get() = from == to

    val signedIn: Boolean get() = user != null

    // a first sign-in, or one to the deployment the app has while signed out: only the address to check
    val new: Boolean get() = from == null || (same && !signedIn)
}
