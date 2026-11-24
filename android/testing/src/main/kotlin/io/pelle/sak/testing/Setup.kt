package io.pelle.sak.testing

import io.pelle.sak.core.SakSetup
import io.pelle.sak.core.randomNonce
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.update.Updater
import kotlinx.coroutines.CoroutineScope
import java.time.Clock

// an updater a test does not expect to be called
val NO_UPDATE_EXPECTED = Updater { error("no update expected") }

// the setup of a model in a test: its work and its lasting work on the scope of the test, the store
// in memory, an api url only as a SAK address, the version of no release
fun CoroutineScope.sakSetup(
    store: SessionStore = MemorySessionStore(),
    clock: Clock = Clock.systemUTC(),
    appVersion: String = "latest",
    updater: Updater? = NO_UPDATE_EXPECTED,
    nonce: () -> String = ::randomNonce,
) = SakSetup(store, scope = this, lasting = this, allowUrl = false, appVersion = appVersion, updater = updater, clock = clock, nonce = nonce)
