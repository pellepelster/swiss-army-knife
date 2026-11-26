package io.pelle.sak.testing

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.pelle.sak.api.model.InfoResponse
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
import io.pelle.sak.core.settings.locked
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.AppUpdate
import io.pelle.sak.core.update.UpdateException
import io.pelle.sak.core.update.Updater
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

// an api with one list, to page through
private class ThingsApi(
    client: HttpClient,
    url: String,
    token: String?,
    languages: String? = "en",
) : SakApi(client, url, token, languages, LoginRequest.Client.GROCERY_ANDROID) {
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
    appVersion: String = "latest",
    updater: Updater? = NO_UPDATE_EXPECTED,
) : SakModel<ThingsApi>(
        scope.sakSetup(store, clock, appVersion, updater, nonce = { "the nonce of the test" }),
        { url, token -> ThingsApi(backend.client, url, token) },
    ) {
    override fun showApp(stored: Stored) {
        state.value = ThingsScreen(stored.email.orEmpty())
    }

    // how often the user changed a setting of theirs
    var settingsChanged = 0

    override fun userSettingsChanged() {
        settingsChanged++
    }

    fun load() =
        scope.launch {
            val things = request(R.string.error_user_not_read, { error -> app { it.copy(error = error) } }) { it.things() } ?: return@launch
            app { it.copy(things = things.size, error = null) }
        }

    // the answer of request as it comes, for the test of an answer of a session that went
    fun fetch(answers: MutableList<Int?>) =
        scope.launch { answers += request(R.string.error_user_not_read, {}) { it.things().size } }

    private fun app(change: (ThingsScreen) -> ThingsScreen) {
        (state.value as? ThingsScreen)?.let { state.value = change(it) }
    }
}

class SakModelTest {
    private val now = Instant.parse("2026-11-02T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["POST /auth/login/link"] = HttpStatusCode.Accepted to ""
            answers["POST /auth/login/exchange"] = HttpStatusCode.OK to session("link-session", "pelle@pelle.io")
            answers["POST /auth/login/password"] = HttpStatusCode.OK to session("password-session", "admin")
            answers["GET /auth/who-am-i"] = HttpStatusCode.OK to whoAmI("admin")
        }

    private fun thing(id: Int) = """{"id":$id}"""

    private fun TestScope.model(
        store: SessionStore,
        at: Instant = now,
        appVersion: String = "latest",
        updater: Updater? = NO_UPDATE_EXPECTED,
    ): ThingsModel {
        backend.answerOn(this)
        return ThingsModel(store, backend, this, Clock.fixed(at, ZoneOffset.UTC), appVersion, updater)
    }

    private fun ThingsModel.app() = screen.value as ThingsScreen

    private fun signedIn(token: String = "session") = MemorySessionStore(signedIn(now.plusSeconds(3600), token))

    private fun signedOut() = MemorySessionStore(io.pelle.sak.testing.signedOut())

    @Test
    fun `the settings of the user open over the app, a switch is saved when turned and the app is told`() =
        runTest {
            backend.answers["GET /user/settings"] =
                HttpStatusCode.OK to userSettings(userScope("notes", "Notes", aiSetting(false)), userScope("grocery", "Grocery", aiSetting(false, available = false)))
            backend.answers["PUT /user/settings/notes"] = HttpStatusCode.OK to json(userScope("notes", "Notes", aiSetting(true)))
            val model = model(signedIn()).also { it.start() }
            advanceUntilIdle()
            assertNull(model.userSettings.dialog.value)

            model.userSettings.open()
            advanceUntilIdle()
            val scopes = model.userSettings.dialog.value!!.scopes!!
            assertEquals(listOf("notes", "grocery"), scopes.map { it.name })
            // without an AI set up it can not be turned on
            assertFalse(scopes[0].settings.single().locked)
            assertTrue(scopes[1].settings.single().locked)

            model.userSettings.set("notes", "ai", true)
            advanceUntilIdle()
            assertTrue("PUT /user/settings/notes {\"ai\":true}" in backend.requests)
            assertEquals(JsonPrimitive(true), model.userSettings.dialog.value!!.scopes!![0].settings.single().value)
            assertEquals(1, model.settingsChanged)

            // a switch that could not be saved stays as it was, the dialog says why, the app is not told
            backend.answers["PUT /user/settings/notes"] = HttpStatusCode.Conflict to refused("No AI is set up.")
            model.userSettings.set("notes", "ai", false)
            advanceUntilIdle()
            model.userSettings.dialog.value!!.let {
                assertEquals(UiText.Plain("No AI is set up."), it.error)
                assertFalse(it.saving)
                assertEquals(JsonPrimitive(true), it.scopes!![0].settings.single().value)
            }
            assertEquals(1, model.settingsChanged)

            model.userSettings.close()
            assertNull(model.userSettings.dialog.value)
            // the end of the session closes it
            model.userSettings.open()
            advanceUntilIdle()
            model.changeDeployment()
            advanceUntilIdle()
            assertNull(model.userSettings.dialog.value)
        }

    @Test
    fun `settings that could not be read say why`() =
        runTest {
            backend.answers["GET /user/settings"] = HttpStatusCode.BadGateway to ""
            val model = model(signedIn()).also { it.start() }
            advanceUntilIdle()
            model.userSettings.open()
            advanceUntilIdle()
            model.userSettings.dialog.value!!.let {
                assertNull(it.scopes)
                assertEquals(uiText(R.string.error_settings_not_read), it.error)
            }
        }

    @Test
    fun `the answer of a session that went changes nothing, also when it worked (AA-1)`() =
        runTest {
            backend.answers["GET /things"] = HttpStatusCode.OK to page(thing(1))
            val model = model(signedIn()).also { it.start() }
            advanceUntilIdle()

            val answers = mutableListOf<Int?>()
            // asked, and the deployment changes before the answer comes
            model.fetch(answers)
            model.changeDeployment()
            advanceUntilIdle()
            assertEquals(listOf<Int?>(null), answers)

            // the same request of a session that stays answers
            val kept = model(signedIn()).also { it.start() }
            advanceUntilIdle()
            kept.fetch(answers)
            advanceUntilIdle()
            assertEquals(listOf(null, 1), answers)
        }

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
            assertEquals(250, model.app().things)
            assertEquals(3, backend.queries.count { it.startsWith("GET /things") })

            // a server that claims more than it gives ends with its short page, no endless paging
            backend.queries.clear()
            backend.answersByQuery["GET /things?offset=0&limit=100"] = HttpStatusCode.OK to page(0, 1_000_000, (0 until 100).map(::thing))
            backend.answersByQuery["GET /things?offset=100&limit=100"] = HttpStatusCode.OK to page(100, 1_000_000, (0 until 7).map(::thing))
            model.load()
            advanceUntilIdle()
            assertEquals(107, model.app().things)
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
            assertEquals("The user could not be loaded.", model.app().error.english())

            backend.answers["GET /things"] = HttpStatusCode.TooManyRequests to ""
            model.load()
            advanceUntilIdle()
            assertEquals("Too many attempts, please try again later.", model.app().error.english())
        }

    @Test
    fun `a link is exchanged with the nonce of its request, once, a link while signed in changes nothing`() =
        runTest {
            val store = signedOut()
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

            // tapped twice: one exchange, the second link is not taken
            model.signInWithLink("login-token")
            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/exchange") })
            assertTrue(backend.requests.contains("""POST /auth/login/exchange {"token":"login-token","nonce":"the nonce of the test"}"""))
            assertEquals("pelle@pelle.io", model.app().user)

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
            val store = MemorySessionStore(signedOut().load().copy(pending = pending))
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
            val memory = signedOut()
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
            model = model(store).also { it.start() }
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
            val model = model(store).also { it.start() }
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
            val store = signedOut()
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
            val model = model(signedOut()).also { it.start() }
            advanceUntilIdle()
            assertNull((model.screen.value as Screen.SignIn).multiUser)

            backend.answers["GET /info"] = HttpStatusCode.OK to info()
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

    private val code = "$ADDRESS/connect#token=the.connect-token"

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
            model.qr.open()
            assertEquals(QrScan(), model.qr.scan.value)
            model.qr.scanned(code)
            assertTrue(model.qr.scan.value!!.busy)
            // the camera reads the code again while it is asked for: once
            model.qr.scanned(code)
            advanceUntilIdle()
            // asked first, with the address of the code (SECURITY-3); nothing is sent before
            assertEquals(QrSwitch(null, ADDRESS, null), model.qr.scan.value!!.confirm)
            assertTrue(model.qr.scan.value!!.confirm!!.new)
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/connect") })
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/connect") })
            assertTrue(backend.requests.contains("""POST /auth/login/connect {"token":"the.connect-token"}"""))
            // the SAK address of the code names its api, which answers as a SAK instance
            assertTrue(backend.requests.contains("GET /.well-known/sak.json"))
            assertNull(backend.tokens["/auth/login/connect"])
            assertEquals("anna@example.org", model.app().user)
            assertNull(model.qr.scan.value)
            val stored = store.load()
            assertEquals(URL, stored.apiUrl)
            assertEquals(ADDRESS, stored.address)
            assertEquals("qr-session", stored.token)
        }

    @Test
    fun `a code that is no code of SAK, refused, used too often or of a deployment out of reach says why`() =
        runTest {
            val model = model(signedOut()).also { it.start() }
            advanceUntilIdle()
            model.qr.open()
            model.qr.scanned("https://evil.example.org/login?token=abc")
            assertEquals(
                "This is not a QR code of SAK. Scan the code the web app shows under “Connect an app”.",
                model.qr.scan.value!!.error.english(),
            )
            // the error waits for the user: no other code is taken meanwhile
            model.qr.scanned(code)
            advanceUntilIdle()
            assertNull(model.qr.scan.value!!.confirm)
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/connect") })

            // the error body of the backend, the code is not valid, used or expired
            backend.answers["POST /auth/login/connect"] =
                HttpStatusCode.Unauthorized to refused("The QR code is not valid.", "auth.code_invalid")
            model.qr.scanAgain()
            model.qr.scanned(code)
            advanceUntilIdle()
            // the deployment the app has, signed out: asked with its address only
            assertTrue(model.qr.scan.value!!.confirm!!.new)
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals("The QR code is invalid, expired or was already used. Show a new one in the web app.", model.qr.scan.value!!.error.english())
            assertFalse(model.qr.scan.value!!.busy)

            backend.answers["POST /auth/login/connect"] = HttpStatusCode.TooManyRequests to ""
            model.qr.scanAgain()
            model.qr.scanned(code)
            advanceUntilIdle()
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals("Too many attempts, please try again later.", model.qr.scan.value!!.error.english())

            backend.unreachable += "POST /auth/login/connect"
            model.qr.scanAgain()
            model.qr.scanned(code)
            advanceUntilIdle()
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals("The SAK instance https://sak.example.org could not be reached.", model.qr.scan.value!!.error.english())

            // no SAK instance answers at the address of the code
            backend.unreachable.clear()
            backend.answers["GET /.well-known/sak.json"] = HttpStatusCode.NotFound to ""
            model.qr.scanAgain()
            model.qr.scanned(code)
            advanceUntilIdle()
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals("The SAK instance https://sak.example.org could not be reached.", model.qr.scan.value!!.error.english())
            // the sign-in stays below the scan
            assertTrue(model.screen.value is Screen.SignIn)
            model.qr.close()
            assertNull(model.qr.scan.value)
        }

    @Test
    fun `signed out, a code of another host asks to switch, kept the stored deployment stays, nothing is sent`() =
        runTest {
            backend.answers["POST /auth/login/connect"] = HttpStatusCode.OK to session("qr-session", "anna@example.org")
            // the deployment the app has; no session
            val store = MemorySessionStore(Stored(apiUrl = "https://api.old.example.org/v1", address = "https://old.example.org"))
            val before = store.load()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.qr.open()
            model.qr.scanned(code)
            advanceUntilIdle()
            val confirm = model.qr.scan.value!!.confirm!!
            assertEquals(QrSwitch("https://old.example.org", ADDRESS, null), confirm)
            assertFalse(confirm.new)
            assertFalse(confirm.signedIn)

            // cancelled: the app keeps the deployment it had, nothing went to the host of the code
            model.qr.close()
            advanceUntilIdle()
            assertNull(model.qr.scan.value)
            assertEquals(before, store.load())
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/connect") })

            // confirmed: it switches
            model.qr.open()
            model.qr.scanned(code)
            advanceUntilIdle()
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals(ADDRESS, store.load().address)
            assertEquals("anna@example.org", model.app().user)
        }

    @Test
    fun `signed in to another deployment, a code asks first, kept nothing changes, confirmed the session is replaced`() =
        runTest {
            backend.answers["POST /auth/login/connect"] = HttpStatusCode.OK to session("qr-session", "anna@example.org")
            val store = signedInElsewhere()
            val before = store.load()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.qr.open()
            model.qr.scanned(code)
            advanceUntilIdle()
            assertEquals(QrSwitch("https://old.example.org", ADDRESS, "admin"), model.qr.scan.value!!.confirm)
            assertFalse(model.qr.scan.value!!.confirm!!.same)
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/connect") })

            // kept: the session stays
            model.qr.close()
            advanceUntilIdle()
            assertNull(model.qr.scan.value)
            assertEquals(before, store.load())
            assertTrue(model.screen.value is ThingsScreen)

            model.qr.open()
            model.qr.scanned(code)
            advanceUntilIdle()
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /auth/login/connect") })
            assertNull(model.qr.scan.value)
            assertEquals("anna@example.org", model.app().user)
            assertEquals(signedIn(Instant.parse("2026-12-02T08:00:00Z"), "qr-session", "anna@example.org"), store.load())
        }

    @Test
    fun `signed in to the same deployment, a code asks whether to sign in again, a refused code keeps the session`() =
        runTest {
            backend.answers["POST /auth/login/connect"] = HttpStatusCode.Unauthorized to ""
            val store = signedIn()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.qr.open()
            model.qr.scanned(code)
            advanceUntilIdle()
            assertTrue(model.qr.scan.value!!.confirm!!.same)
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals("The QR code is invalid, expired or was already used. Show a new one in the web app.", model.qr.scan.value!!.error.english())
            assertEquals("session", store.load().token)
            assertTrue(model.screen.value is ThingsScreen)

            backend.answers["POST /auth/login/connect"] = HttpStatusCode.OK to session("qr-session", "anna@example.org")
            model.qr.scanAgain()
            model.qr.scanned(code)
            advanceUntilIdle()
            model.qr.confirm()
            advanceUntilIdle()
            assertEquals("qr-session", store.load().token)
            assertEquals("anna@example.org", model.app().user)
        }

    @Test
    fun `a camera refused shows the scan without it until the camera is allowed`() =
        runTest {
            val model = model(MemorySessionStore(Stored())).also { it.start() }
            advanceUntilIdle()
            model.qr.cameraDenied()
            // no scan open: nothing
            assertNull(model.qr.scan.value)
            model.qr.open()
            model.qr.cameraDenied()
            assertEquals(QrScan(denied = true), model.qr.scan.value)
            model.qr.cameraAllowed()
            assertEquals(QrScan(), model.qr.scan.value)
            model.qr.cameraDenied()
            model.qr.close()
            assertNull(model.qr.scan.value)
            // the next scan asks again
            model.qr.open()
            assertEquals(QrScan(), model.qr.scan.value)
            assertEquals(Screen.Deployment(), model.screen.value)
        }

    @Test
    fun `a new app asks for the deployment, checks it and offers the sign-in it has`() =
        runTest {
            val store = MemorySessionStore()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.Deployment(), model.screen.value)

            model.connect("nonsense")
            assertTrue((model.screen.value as Screen.Deployment).error.english()!!.contains("SAK address"))

            model.connect("sak.example.org")
            advanceUntilIdle()
            assertEquals(Screen.SignIn(ADDRESS, multiUser = true), model.screen.value)
            assertEquals(io.pelle.sak.testing.signedOut(), store.load())
        }

    @Test
    fun `an address that names no api, or one without https, is no SAK address`() =
        runTest {
            val store = MemorySessionStore()
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            backend.answers["GET /.well-known/sak.json"] = HttpStatusCode.NotFound to ""
            model.connect("www.example.org")
            advanceUntilIdle()
            assertEquals("This is not a SAK address.", (model.screen.value as Screen.Deployment).error.english())

            backend.answers["GET /.well-known/sak.json"] = HttpStatusCode.OK to """{"api":"http://api.example.org/v1"}"""
            model.connect("sak.example.org")
            advanceUntilIdle()
            assertEquals("This is not a SAK address.", (model.screen.value as Screen.Deployment).error.english())
            assertEquals(Stored(), store.load())
        }

    @Test
    fun `the password signs in to the app and keeps the session`() =
        runTest {
            val store = signedOut()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /auth/login/password {"password":"a password of sixteen"}"""))
            assertEquals("admin", model.app().user)
            assertEquals("password-session", store.load().token)
        }

    @Test
    fun `a login link is taken only for a login this app asked for, within 15 minutes`() =
        runTest {
            val store = signedOut()
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            // a link nobody asked for in this app
            model.signInWithLink("foreign")
            advanceUntilIdle()
            assertEquals(SakModel.NOT_ASKED, (model.screen.value as Screen.SignIn).error)
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/exchange") })

            // the address typed with spaces
            model.requestLink(" pelle@pelle.io ")
            advanceUntilIdle()
            assertEquals("pelle@pelle.io", (model.screen.value as Screen.SignIn).linkSentTo)
            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertEquals("pelle@pelle.io", model.app().user)
            // used once
            assertNull(store.load().pending)
        }

    @Test
    fun `a pending login older than 15 minutes is no longer taken`() =
        runTest {
            val store = MemorySessionStore(io.pelle.sak.testing.signedOut().copy(pending = PendingLogin(URL, now.minusSeconds(16 * 60), "a nonce of sixteen")))
            val model = model(store).also { it.start() }
            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertEquals(SakModel.NOT_ASKED, (model.screen.value as Screen.SignIn).error)
        }

    @Test
    fun `a session that ended leads to the sign-in, the deployment stays`() =
        runTest {
            backend.answers["GET /things"] = HttpStatusCode.Unauthorized to ""
            val store = signedIn()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.load()
            advanceUntilIdle()

            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
            assertNull(store.load().token)
            assertEquals(URL, store.load().apiUrl)
        }

    @Test
    fun `an expired session is not used, signing out keeps the deployment`() =
        runTest {
            val expired = MemorySessionStore(signedIn(now.minusSeconds(1), "old"))
            model(expired).also { it.start() }.let {
                advanceUntilIdle()
                assertTrue(it.screen.value is Screen.SignIn)
            }

            val store = signedIn()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signOut()
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
            assertEquals(io.pelle.sak.testing.signedOut(), store.load())
        }

    @Test
    fun `a sign out everywhere ends the session here after the backend ended all, a failure keeps it (SEC-4)`() =
        runTest {
            val store = signedIn()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            val failures = mutableListOf<UiText>()

            backend.answers["POST /auth/sign-out-everywhere"] = HttpStatusCode.InternalServerError to ""
            model.signOutEverywhere { failures += it }
            advanceUntilIdle()
            assertEquals(listOf<UiText>(uiText(R.string.login_not_signed_out_everywhere)), failures)
            assertFalse(model.screen.value is Screen.SignIn)

            backend.answers["POST /auth/sign-out-everywhere"] = HttpStatusCode.NoContent to ""
            model.signOutEverywhere { failures += it }
            advanceUntilIdle()
            assertEquals(1, failures.size)
            assertTrue(model.screen.value is Screen.SignIn)
            assertEquals(io.pelle.sak.testing.signedOut(), store.load())
        }

    @Test
    fun `an error of the backend is shown with its message`() =
        runTest {
            backend.answers["POST /auth/login/password"] = HttpStatusCode.Unauthorized to ""
            val model = model(signedOut()).also { it.start() }
            advanceUntilIdle()
            model.signInWithPassword("wrong")
            advanceUntilIdle()
            assertEquals("The password is wrong.", (model.screen.value as Screen.SignIn).error.english())

            backend.answers["POST /auth/login/password"] = HttpStatusCode.TooManyRequests to refused("Slow down.")
            model.signInWithPassword("again")
            advanceUntilIdle()
            assertEquals("Slow down.", (model.screen.value as Screen.SignIn).error.english())
        }

    @Test
    fun `the sign-in offers its three ways, a way chosen shows its form, back the three again, no e-mail in the single user mode`() =
        runTest {
            val multi = model(signedOut()).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.SignIn(ADDRESS, multiUser = true), multi.screen.value)
            multi.chooseMethod(Screen.SignInMethod.PASSWORD)
            assertEquals(Screen.SignInMethod.PASSWORD, (multi.screen.value as Screen.SignIn).method)
            multi.chooseMethod(null)
            assertEquals(null, (multi.screen.value as Screen.SignIn).method)
            multi.chooseMethod(Screen.SignInMethod.EMAIL)
            assertEquals(Screen.SignInMethod.EMAIL, (multi.screen.value as Screen.SignIn).method)

            backend.answers["GET /info"] = HttpStatusCode.OK to info(userMode = InfoResponse.UserMode.SINGLE)
            val single = model(signedOut()).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.SignIn(ADDRESS, multiUser = false), single.screen.value)
            // no e-mail login to go to
            single.chooseMethod(Screen.SignInMethod.EMAIL)
            assertEquals(null, (single.screen.value as Screen.SignIn).method)
            single.chooseMethod(Screen.SignInMethod.PASSWORD)
            assertEquals(Screen.SignInMethod.PASSWORD, (single.screen.value as Screen.SignIn).method)
        }

    @Test
    fun `the menu shows the name of the user, else their address`() =
        runTest {
            backend.answers["GET /auth/who-am-i"] = HttpStatusCode.OK to whoAmI("anna@example.org", "Anna")
            val named = model(MemorySessionStore(signedIn(now.plusSeconds(3600), email = "anna@example.org"))).also { it.start() }
            advanceUntilIdle()
            assertEquals("Anna", named.app().user)

            backend.answers["GET /auth/who-am-i"] = HttpStatusCode.OK to whoAmI("anna@example.org")
            val unnamed = model(MemorySessionStore(signedIn(now.plusSeconds(3600), email = "anna@example.org"))).also { it.start() }
            advanceUntilIdle()
            assertEquals("anna@example.org", unnamed.app().user)
        }

    @Test
    fun `the api asks the backend for its texts in the languages of the phone`() =
        runTest {
            backend.answerOn(this)
            ThingsApi(backend.client, URL, null, "de-DE,en-US").info()
            ThingsApi(backend.client, URL, null, null).info()
            assertEquals(listOf("de-DE,en-US", null), backend.languages)
        }

    @Test
    fun `the app offers the version of the deployment when it is newer, the update hands it to the installer`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            val installed = mutableListOf<String>()
            val model = model(signedIn(), appVersion = "0.1.14", updater = { installed += it }).also { it.start() }
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15"), model.app().update)

            model.updates.install()
            assertEquals(AppUpdate("0.1.15", busy = true), model.app().update)
            advanceUntilIdle()
            assertEquals(listOf("0.1.15"), installed)
            assertEquals(AppUpdate("0.1.15"), model.app().update)

            // the same version offers nothing
            val current = model(signedIn(), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            assertNull(current.app().update)
        }

    @Test
    fun `an update that fails says why and can be tried again`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            var failure: Exception = IOException("offline")
            val model = model(signedIn(), appVersion = "0.1.14", updater = { throw failure }).also { it.start() }
            advanceUntilIdle()

            model.updates.install()
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15", error = uiText(R.string.error_update_not_downloaded)), model.app().update)

            failure = UpdateException(uiText(R.string.error_update_damaged, "0.1.15"))
            model.updates.install()
            advanceUntilIdle()
            assertEquals("The download of version 0.1.15 is damaged, please try again.", model.app().update?.error.english())
        }

    private fun ThingsModel.notice() = app().updateNotice.english()

    @Test
    fun `the check for updates offers a newer version, else says why there is none`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            val model = model(signedIn(), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()

            model.updates.check()
            assertEquals("Checking for updates…", model.notice())
            advanceUntilIdle()
            assertEquals("The app is up-to-date, version 0.1.15.", model.notice())
            model.updates.dismissNotice()
            assertNull(model.notice())

            // the deployment was updated since the start
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.16")
            model.updates.check()
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.16"), model.app().update)
            assertNull(model.notice())

            backend.answers["GET /info"] = HttpStatusCode.OK to info("latest")
            val current = model(signedIn(), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            current.updates.check()
            advanceUntilIdle()
            assertEquals("Your SAK instance does not run a release, so there is no update for the app.", current.notice())

            val debug = model(signedIn(), appVersion = "latest").also { it.start() }
            advanceUntilIdle()
            debug.updates.check()
            advanceUntilIdle()
            assertEquals("This build of the app is no release, it is not updated.", debug.notice())
        }

    @Test
    fun `the build of google play has no updater and offers no update, google play updates it`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            val model = model(signedIn(), appVersion = "0.1.14", updater = null).also { it.start() }
            advanceUntilIdle()
            assertFalse(model.updates.enabled)
            assertNull(model.app().update)

            model.updates.check()
            model.updates.install()
            advanceUntilIdle()
            assertNull(model.app().update)
            assertNull(model.notice())
        }

    @Test
    fun `a check for updates that fails says why`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            val model = model(signedIn(), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            backend.answers["GET /info"] = HttpStatusCode.InternalServerError to refused("Something went wrong.", "api.unexpected_error")
            model.updates.check()
            advanceUntilIdle()
            assertEquals("Something went wrong.", model.notice())
        }

    // signed in for a month, so a start a week later still finds the session
    private fun monthStore(checkedAt: Instant? = null) = MemorySessionStore(signedIn(now.plus(Duration.ofDays(30))).copy(updateCheckedAt = checkedAt))

    private fun asked() = backend.requests.count { it == "GET /info" }

    @Test
    fun `the app asks for its update by itself at most once in 7 days (UX-13)`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            val store = monthStore()
            val first = model(store, appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15"), first.app().update)
            assertEquals(now, store.load().updateCheckedAt)
            assertEquals(1, asked())

            // 6 days later: nothing is asked and nothing offered
            val sixDays = model(store, at = now.plus(Duration.ofDays(6)), appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            assertNull(sixDays.app().update)
            assertEquals(1, asked())

            // 7 days later: asked again
            val week = model(store, at = now.plus(Duration.ofDays(7)), appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15"), week.app().update)
            assertEquals(now.plus(Duration.ofDays(7)), store.load().updateCheckedAt)
            assertEquals(2, asked())
        }

    @Test
    fun `a check that failed is no check, the next start asks again`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            backend.unreachable += "GET /info"
            val store = monthStore()
            val offline = model(store, appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            assertNull(offline.app().update)
            assertNull(store.load().updateCheckedAt)

            backend.unreachable -= "GET /info"
            val online = model(store, at = now.plusSeconds(60), appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15"), online.app().update)
        }

    @Test
    fun `the check of the menu asks at once and counts as the check of the week`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            val store = monthStore(checkedAt = now)
            val day = now.plus(Duration.ofDays(1))
            val model = model(store, at = day, appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            assertNull(model.app().update)
            assertEquals(0, asked())

            model.updates.check()
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15"), model.app().update)
            assertEquals(day, store.load().updateCheckedAt)
        }

    @Test
    fun `later hides the update until the next check`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            val store = monthStore()
            val model = model(store, appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            model.updates.later()
            assertNull(model.app().update)

            // a start within the week offers nothing
            val again = model(store, at = now.plus(Duration.ofDays(1)), appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()
            assertNull(again.app().update)

            // the check of the menu offers it again
            again.updates.check()
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15"), again.app().update)
        }

    @Test
    fun `the time of the last check stays over a sign-out and goes with another deployment`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to info("0.1.15")
            backend.answers["POST /auth/logout"] = HttpStatusCode.NoContent to ""
            val store = monthStore(checkedAt = now)
            val model = model(store, appVersion = "0.1.14").also { it.start() }
            advanceUntilIdle()

            model.signOut()
            advanceUntilIdle()
            assertEquals(now, store.load().updateCheckedAt)

            model.changeDeployment()
            advanceUntilIdle()
            assertNull(store.load().updateCheckedAt)
        }
}
