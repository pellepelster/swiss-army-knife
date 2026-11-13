package io.pelle.sak.core.lists

import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.session.Stored
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

// the changes of the lists while the app is in the foreground (LIST-4). A stream that ends is opened
// again after a longer wait each time, up to MAX_RETRY_DELAY_MS; the wait starts short again only after
// a stream stayed open STABLE_STREAM, so one that closes at once does not load everything every second
class ChangeStream(
    private val scope: CoroutineScope,
    private val clock: Clock,
    // waits until the stream may open (the lists show), then the session to open it with; null ends
    // the listening, e.g. without a deployment
    private val session: suspend () -> Stored?,
    // the ids of the lists that change; opened is called once the stream is open
    private val changes: (Stored, opened: suspend () -> Unit) -> Flow<Int>,
    // the session ended (a 401), with the token that was refused
    private val ended: suspend (ApiException, String?) -> Unit,
    // the stream is open again after it was open before: the changes meanwhile were missed
    private val onReconnect: () -> Unit,
    // a list changed
    private val onChange: (Int) -> Unit,
) {
    private var listening: Job? = null

    // a new stream after an open one may have missed changes
    private var openedBefore = false

    fun start() {
        if (listening?.isActive == true) return
        listening =
            scope.launch {
                var wait = RETRY_DELAY_MS
                while (isActive) {
                    val stored = session() ?: return@launch
                    var openedAt: Instant? = null
                    try {
                        changes(stored) {
                            openedAt = clock.instant()
                            if (openedBefore) onReconnect()
                            openedBefore = true
                        }.collect(onChange)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.status == 401) ended(e, stored.token)
                    } catch (_: Exception) {
                        // the connection ended, it is opened again
                    }
                    if (openedAt?.let { Duration.between(it, clock.instant()) >= STABLE_STREAM } == true) wait = RETRY_DELAY_MS
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
    }

    fun stop() {
        listening?.cancel()
        listening = null
    }

    // the session or the deployment changed: a stream that is open goes, with the token it was opened
    // with, and opens again for what is stored now
    fun restart() {
        val wasListening = listening != null
        stop()
        openedBefore = false
        if (wasListening) start()
    }

    companion object {
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 30_000L
        val STABLE_STREAM: Duration = Duration.ofSeconds(30)
    }
}
