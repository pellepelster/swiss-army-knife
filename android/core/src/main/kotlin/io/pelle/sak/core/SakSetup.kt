package io.pelle.sak.core

import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.update.Updater
import kotlinx.coroutines.CoroutineScope
import java.time.Clock

// what every model of an app takes besides its api, made once by SakViewModel; the tests make it with
// sakSetup of testing
class SakSetup(
    // where the session is kept
    val store: SessionStore,
    // the scope of the model, the one of the view model
    val scope: CoroutineScope,
    // where work goes that must not end with the screen, e.g. the last save when the app closes; the
    // scope of the process in the app (SakProcess), the scope of the model in the tests
    val lasting: CoroutineScope,
    // the debug build takes a whole url of the api, e.g. of the backend of local:run
    val allowUrl: Boolean,
    // the version of the app, 1.4.2 of a release, latest of every other build
    val appVersion: String,
    // none in the build of google play, which updates the app itself: the model offers no update
    val updater: Updater?,
    val clock: Clock = Clock.systemUTC(),
    // the nonce of each login request
    val nonce: () -> String = ::randomNonce,
)
