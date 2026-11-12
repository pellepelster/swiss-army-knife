package io.pelle.sak.testing

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.pelle.sak.api.model.LoginRequest
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.R
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.Screen
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.qr.QrScan
import io.pelle.sak.core.qr.QrSwitch
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.PendingLogin
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.update.AppUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// an api with one list, to page through
private class ThingsApi(
    client: HttpClient,
    url: String,
    token: String?,
) : SakApi(client, url, token, "en", LoginRequest.Client.GROCERY_ANDROID) {
    suspend fun things(): List<JsonObject> = all("$url/things", R.string.error_user_not_read)
}

// the screen of the app: the things loaded
private data class ThingsScreen(
    override val user: String,
    override val update: AppUpdate? = null,
    override val updateNotice: UiText? = null,
    val things: Int = 0,
    val error: UiText? = null,
) : AppScreen {
    override fun withUser(user: String) = copy(user = user)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)
}

// the logic of core with an app as small as it gets
private class ThingsModel(
    store: SessionStore,
    backend: FakeBackend,
    scope: TestScope,
    clock: Clock,
) : SakModel<ThingsApi>(
        store,
        { url, token -> ThingsApi(backend.client, url, token) },
        scope,
        allowUrl = false,
        clock = clock,
        nonce = { "the nonce of the test" },
    ) {
    override fun showApp(stored: Stored) {
        state.value = ThingsScreen(stored.email.orEmpty())
    }

    fun load() =
        scope.launch {
            val things = request(R.string.error_user_not_read, { error -> app { it.copy(error = error) } }) { it.things() } ?: return@launch
            app { it.copy(things = things.size, error = null) }
        }

    private fun app(change: (ThingsScreen) -> ThingsScreen) {
        (state.value as? ThingsScreen)?.let { state.value = change(it) }
    }
}

class SakModelTest {
    private val now = Instant.parse("2026-11-02T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["POST /auth/login/link"] = HttpStatusCode.Accepted to ""
            answers["POST /auth/login/exchange"] = HttpStatusCode.OK to session("link-session", "pelle@pelle.io")
            answers["POST /auth/login/password"] = HttpStatusCode.OK to session("password-session", "admin")
            answers["GET /auth/who-am-i"] = HttpStatusCode.OK to """{"email":"admin","roles":["admin"]}"""
        }

    init {
        // the texts of core only; the tests of testing have no app
        System.setProperty("sak.app.package", "io.pelle.sak.testing")
    }

    private fun session(
        token: String,
        email: String,
    ) = """{"sessionToken":"$token","email":"$email","expiresAt":"2026-12-02T08:00:00.000Z","roles":["admin"],"redirect":"/"}"""

    private fun thing(id: Int) = """{"id":$id}"""

    private fun TestScope.model(store: MemorySessionStore): ThingsModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        return ThingsModel(store, backend, this, Clock.fixed(now, ZoneOffset.UTC))
    }

    private fun signedIn(token: String = "session") =
        MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org", token = token, email = "admin", expiresAt = now.plusSeconds(3600)))

    @Test
    fun `every page of a list is asked for, a short page or the total ends it`() =
        runTest {
            backend.answersByQuery["GET /things?offset=0&limit=100"] = HttpStatusCode.OK to page(0, 250, (0 until 100).map(::thing))
            backend.answersByQuery["GET /things?offset=100&limit=100"] = HttpStatusCode.OK to page(100, 250, (100 until 200).map(::thing))
            backend.answersByQuery["GET /things?offset=200&limit=100"] = HttpStatusCode.OK to page(200, 250, (200 until 250).map(::thing))
            val model = model(signedIn()).also { it.start() }
            advanceUntilIdle()
            model.load()
            advanceUntilIdle()
            assertEquals(250, (model.screen.value as ThingsScreen).things)
            assertEquals(3, backend.queries.count { it.startsWith("GET /things") })

            // a server that claims more than it gives ends with its short page (AN-38)
            backend.queries.clear()
            backend.answersByQuery["GET /things?offset=0&limit=100"] = HttpStatusCode.OK to page(0, 1_000_000, (0 until 100).map(::thing))
            backend.answersByQuery["GET /things?offset=100&limit=100"] = HttpStatusCode.OK to page(100, 1_000_000, (0 until 7).map(::thing))
            model.load()
            advanceUntilIdle()
            assertEquals(107, (model.screen.value as ThingsScreen).things)
            assertEquals(2, backend.queries.count { it.startsWith("GET /things") })
        }

    @Test
    fun `an error that is no json shows the text of the app, too many requests says so`() =
        runTest {
            backend.answers["GET /things"] = HttpStatusCode.InternalServerError to "<html>oops</html>"
            backend.contentTypes["GET /things"] = "text/html"
            val model = model(signedIn()).also { it.start() }
            advanceUntilIdle()
            model.load()
            advanceUntilIdle()
            assertEquals("The user could not be read.", (model.screen.value as ThingsScreen).error.english())

            backend.answers["GET /things"] = HttpStatusCode.TooManyRequests to ""
            model.load()
            advanceUntilIdle()
            assertEquals("Too many attempts, please try again later.", (model.screen.value as ThingsScreen).error.english())
        }

    @Test
    fun `a link is exchanged with the nonce of its request, once, a link while signed in changes nothing`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.requestLink("pelle@pelle.io")
            advanceUntilIdle()
            assertTrue(
                backend.requests.contains(
                    """POST /auth/login/link {"email":"pelle@pelle.io","client":"grocery-android","nonce":"the nonce of the test"}""",
                ),
            )
            assertEquals(PendingLogin(URL, now, "the nonce of the test"), store.load().pending)

            // tapped twice: one exchange, the second link is not taken (AN-2)
            model.signInWithLink("login-token")
            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/exchange") })
            assertTrue(backend.requests.contains("""POST /auth/login/exchange {"token":"login-token","nonce":"the nonce of the test"}"""))
            assertEquals("pelle@pelle.io", (model.screen.value as ThingsScreen).user)

            // signed in, e.g. a link android gives again: nothing
            model.signInWithLink("another-token")
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/exchange") })
            assertTrue(model.screen.value is ThingsScreen)
        }

    @Test
    fun `a refused link keeps the login pending, the right link still signs in`() =
        runTest {
            val pending = PendingLogin(URL, now, "the nonce of the test")
            val store = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org", pending = pending))
            backend.answers["POST /auth/login/exchange"] = HttpStatusCode.Unauthorized to ""
            val model = model(store).also { it.start() }
            model.signInWithLink("someone-elses-token")
            advanceUntilIdle()
            assertTrue((model.screen.value as Screen.SignIn).error != null)
            assertEquals(pending, store.load().pending)

            backend.answers["POST /auth/login/exchange"] = HttpStatusCode.OK to session("link-session", "pelle@pelle.io")
            model.signInWithLink("the-right-token")
            advanceUntilIdle()
            assertTrue(model.screen.value is ThingsScreen)
            assertNull(store.load().pending)
        }

    @Test
    fun `a sign-in that answers after another deployment was chosen changes nothing`() =
        runTest {
            val memory = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))
            var model: ThingsModel? = null
            // another deployment is chosen right after the sign-in read the deployment
            var switch = false
            val store =
                object : SessionStore by memory {
                    override suspend fun load(): Stored =
                        memory.load().also {
                            if (switch) {
                                switch = false
                                model!!.changeDeployment()
                            }
                        }
                }
            backend.dispatcher = StandardTestDispatcher(testScheduler)
            model = ThingsModel(store, backend, this, Clock.fixed(now, ZoneOffset.UTC)).also { it.start() }
            advanceUntilIdle()
            switch = true
            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()
            assertTrue(backend.requests.any { it.startsWith("POST /auth/login/password") })
            assertEquals(Screen.Deployment(), model.screen.value)
            assertEquals(Stored(), memory.load())
        }

    @Test
    fun `a request of an older session that ends with 401 leaves the newer session`() =
        runTest {
            backend.answers["GET /things"] = HttpStatusCode.Unauthorized to ""
            val memory = signedIn(token = "old")
            // the user signs in again right after the request read the old session
            var switch = false
            val store =
                object : SessionStore by memory {
                    override suspend fun load(): Stored =
                        memory.load().also {
                            if (switch) {
                                switch = false
                                memory.update { stored -> stored.copy(token = "new") }
                            }
                        }
                }
            backend.dispatcher = StandardTestDispatcher(testScheduler)
            val model = ThingsModel(store, backend, this, Clock.fixed(now, ZoneOffset.UTC)).also { it.start() }
            advanceUntilIdle()
            switch = true
            model.load()
            advanceUntilIdle()
            assertEquals("new", store.load().token)
            assertTrue(model.screen.value.toString(), model.screen.value is ThingsScreen)

            // the 401 of the session stored ends it
            model.load()
            advanceUntilIdle()
            assertNull(store.load().token)
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
        }

    @Test
    fun `a sign-in is asked for once while it runs, the screen after a link offers it again or another address`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signInWithPassword("a password of sixteen")
            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/password") })

            model.signOut()
            advanceUntilIdle()
            model.requestLink("pelle@pelle.io")
            advanceUntilIdle()
            assertEquals("pelle@pelle.io", (model.screen.value as Screen.SignIn).linkSentTo)
            model.otherAddress()
            assertNull((model.screen.value as Screen.SignIn).linkSentTo)
        }

    @Test
    fun `a deployment that did not answer on the sign-in is asked again`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.BadGateway to ""
            val model = model(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))).also { it.start() }
            advanceUntilIdle()
            assertNull((model.screen.value as Screen.SignIn).multiUser)

            backend.answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            model.retrySignIn()
            advanceUntilIdle()
            assertEquals(true, (model.screen.value as Screen.SignIn).multiUser)
        }

    @Test
    fun `the values of the app go with the session`() =
        runTest {
            val store = MemorySessionStore(signedIn().load().copy(app = mapOf("open_list" to "2")))
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signOut()
            advanceUntilIdle()
            assertEquals(emptyMap<String, String>(), store.load().app)
        }

    private val code = "https://sak.example.org/connect#token=the.connect-token"

    // the deployment of the old session, before a code of another one
    private fun signedInElsewhere() =
        MemorySessionStore(
            Stored(apiUrl = "https://api.old.example.org/v1", address = "https://old.example.org", token = "old", email = "admin", expiresAt = now.plusSeconds(3600)),
        )

    @Test
    fun `a QR code signs in to its deployment, from the first screen`() =
        runTest {
            backend.answers["POST /auth/login/connect"] = HttpStatusCode.OK to session("qr-session", "anna@example.org")
            val store = MemorySessionStore(Stored())
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.Deployment(), model.screen.value)
            model.scanQr()
            assertEquals(QrScan(), model.scan.value)
            model.signInWithQr(code)
            assertTrue(model.scan.value!!.busy)
            // the camera reads the code again while it is exchanged: once
            model.signInWithQr(code)
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/connect") })
            assertTrue(backend.requests.contains("""POST /auth/login/connect {"token":"the.connect-token"}"""))
            // the SAK address of the code names its api, which answers as a SAK instance
            assertTrue(backend.requests.contains("GET /.well-known/sak.json"))
            assertNull(backend.tokens["/auth/login/connect"])
            assertEquals("anna@example.org", (model.screen.value as ThingsScreen).user)
            assertNull(model.scan.value)
            val stored = store.load()
            assertEquals(URL, stored.apiUrl)
            assertEquals("https://sak.example.org", stored.address)
            assertEquals("qr-session", stored.token)
        }

    @Test
    fun `a code that is no code of SAK, refused, used too often or of a deployment out of reach says why`() =
        runTest {
            val model = model(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))).also { it.start() }
            advanceUntilIdle()
            model.scanQr()
            model.signInWithQr("https://evil.example.org/login?token=abc")
            assertEquals(
                "This is no QR code of SAK. Scan the code the web app shows under “Connect an app”.",
                model.scan.value!!.error.english(),
            )
            // the error waits for the user: no other code is taken meanwhile
            model.signInWithQr(code)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/connect") })

            // the error body of the backend, the code is not valid, used or expired
            backend.answers["POST /auth/login/connect"] =
                HttpStatusCode.Unauthorized to """{"errors":[{"code":"auth.code_invalid","message":"The QR code is not valid."}]}"""
            model.scanAgain()
            model.signInWithQr(code)
            advanceUntilIdle()
            assertEquals("The QR code is not valid, or it was used or expired. Show a new one in the web app.", model.scan.value!!.error.english())
            assertFalse(model.scan.value!!.busy)

            backend.answers["POST /auth/login/connect"] = HttpStatusCode.TooManyRequests to ""
            model.scanAgain()
            model.signInWithQr(code)
            advanceUntilIdle()
            assertEquals("Too many attempts, please try again later.", model.scan.value!!.error.english())

            backend.unreachable += "POST /auth/login/connect"
            model.scanAgain()
            model.signInWithQr(code)
            advanceUntilIdle()
            assertEquals("The SAK instance https://sak.example.org could not be reached.", model.scan.value!!.error.english())

            // no SAK instance answers at the address of the code
            backend.unreachable.clear()
            backend.answers["GET /.well-known/sak.json"] = HttpStatusCode.NotFound to ""
            model.scanAgain()
            model.signInWithQr(code)
            advanceUntilIdle()
            assertEquals("The SAK instance https://sak.example.org could not be reached.", model.scan.value!!.error.english())
            // the sign-in stays below the scan
            assertTrue(model.screen.value is Screen.SignIn)
            model.closeScan()
            assertNull(model.scan.value)
        }

    @Test
    fun `signed in to another deployment, a code asks first, kept nothing changes, confirmed the session is replaced`() =
        runTest {
            backend.answers["POST /auth/login/connect"] = HttpStatusCode.OK to session("qr-session", "anna@example.org")
            val store = signedInElsewhere()
            val before = store.load()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.scanQr()
            model.signInWithQr(code)
            advanceUntilIdle()
            assertEquals(QrSwitch("https://old.example.org", "https://sak.example.org", "admin"), model.scan.value!!.confirm)
            assertFalse(model.scan.value!!.confirm!!.same)
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/connect") })

            // kept: the session stays
            model.closeScan()
            advanceUntilIdle()
            assertNull(model.scan.value)
            assertEquals(before, store.load())
            assertTrue(model.screen.value is ThingsScreen)

            model.scanQr()
            model.signInWithQr(code)
            advanceUntilIdle()
            model.confirmQr()
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/connect") })
            assertNull(model.scan.value)
            assertEquals("anna@example.org", (model.screen.value as ThingsScreen).user)
            assertEquals(Stored(apiUrl = URL, address = "https://sak.example.org", token = "qr-session", email = "anna@example.org", expiresAt = Instant.parse("2026-12-02T08:00:00Z")), store.load())
        }

    @Test
    fun `signed in to the same deployment, a code asks whether to sign in anew, a refused code keeps the session`() =
        runTest {
            backend.answers["POST /auth/login/connect"] = HttpStatusCode.Unauthorized to ""
            val store = signedIn()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.scanQr()
            model.signInWithQr(code)
            advanceUntilIdle()
            assertTrue(model.scan.value!!.confirm!!.same)
            model.confirmQr()
            advanceUntilIdle()
            assertEquals("The QR code is not valid, or it was used or expired. Show a new one in the web app.", model.scan.value!!.error.english())
            assertEquals("session", store.load().token)
            assertTrue(model.screen.value is ThingsScreen)

            backend.answers["POST /auth/login/connect"] = HttpStatusCode.OK to session("qr-session", "anna@example.org")
            model.scanAgain()
            model.signInWithQr(code)
            advanceUntilIdle()
            model.confirmQr()
            advanceUntilIdle()
            assertEquals("qr-session", store.load().token)
            assertEquals("anna@example.org", (model.screen.value as ThingsScreen).user)
        }

    @Test
    fun `a camera refused shows the scan without it until the camera is allowed`() =
        runTest {
            val model = model(MemorySessionStore(Stored())).also { it.start() }
            advanceUntilIdle()
            model.cameraDenied()
            // no scan open: nothing
            assertNull(model.scan.value)
            model.scanQr()
            model.cameraDenied()
            assertEquals(QrScan(denied = true), model.scan.value)
            model.cameraAllowed()
            assertEquals(QrScan(), model.scan.value)
            model.cameraDenied()
            model.closeScan()
            assertNull(model.scan.value)
            // the next scan asks again
            model.scanQr()
            assertEquals(QrScan(), model.scan.value)
            assertEquals(Screen.Deployment(), model.screen.value)
        }
}
