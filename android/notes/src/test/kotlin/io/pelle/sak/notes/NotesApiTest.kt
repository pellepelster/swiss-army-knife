package io.pelle.sak.notes

import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.api.BUSY_RETRIES
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.URL
import io.pelle.sak.testing.answerOn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NotesApiTest {
    @Test
    fun `a thumbnail the backend is too busy to make is asked for again, a whole file not (SEC-50)`() =
        runTest {
            val backend = FakeBackend().answerOn(this)
            val api = backend.api(URL, "session")
            val busy = HttpStatusCode.ServiceUnavailable to ""
            backend.answersOnce["GET /files/7/thumbnail"] = ArrayDeque(listOf(busy, busy))
            backend.answers["GET /files/7/thumbnail"] = HttpStatusCode.OK to "jpeg"
            assertEquals("jpeg", String(api.file(7, thumbnail = true, failure = R.string.notes_errors_not_loaded)))
            assertEquals(3, backend.requests.count { it == "GET /files/7/thumbnail" })

            // busy for longer than the retries: the failure as before
            backend.answers["GET /files/8/thumbnail"] = busy
            assertEquals(HttpStatusCode.ServiceUnavailable.value, failed { api.file(8, thumbnail = true, failure = R.string.notes_errors_not_loaded) })
            assertEquals(BUSY_RETRIES + 1, backend.requests.count { it == "GET /files/8/thumbnail" })

            backend.answersOnce["GET /files/9"] = ArrayDeque(listOf(busy))
            backend.answers["GET /files/9"] = HttpStatusCode.OK to "wav"
            assertEquals(HttpStatusCode.ServiceUnavailable.value, failed { api.file(9, thumbnail = false, failure = R.string.notes_errors_not_loaded) })
            assertEquals(1, backend.requests.count { it == "GET /files/9" })
        }

    // the status of the ApiException the call ends with
    private suspend fun failed(call: suspend () -> Unit): Int =
        try {
            call()
            fail("no ApiException")
            0
        } catch (error: ApiException) {
            error.status
        }
}
