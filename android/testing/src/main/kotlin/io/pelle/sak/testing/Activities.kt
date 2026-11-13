package io.pelle.sak.testing

import androidx.test.core.app.ActivityScenario
import io.pelle.sak.core.SakProcess
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import kotlinx.coroutines.Dispatchers
import org.robolectric.RuntimeEnvironment

// sets the process up for an activity on robolectric before it starts: the fake backend, the session
// in memory; the answers come at once, on the thread that asks
fun FakeBackend.forActivities(stored: Stored) {
    forgetFileProviders()
    dispatcher = Dispatchers.Unconfined
    SakProcess.engine = engine
    val store = MemorySessionStore(stored)
    SakProcess.sessionStore = { _, _ -> store }
}

// android recreates the activity, the view model stays
fun ActivityScenario<*>.rotate(landscape: Boolean = true) {
    RuntimeEnvironment.setQualifiers(if (landscape) "+land" else "+port")
    recreate()
}

// FileProvider keeps the directories of the app it saw first for good, robolectric gives every test
// other ones, e.g. the cache the camera of the grocery app writes into
fun forgetFileProviders() {
    val cache = Class.forName("androidx.core.content.FileProvider").getDeclaredField("sCache").apply { isAccessible = true }
    synchronized(cache.get(null)!!) { (cache.get(null) as MutableMap<*, *>).clear() }
}
