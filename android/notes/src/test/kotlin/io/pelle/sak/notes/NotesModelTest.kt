package io.pelle.sak.notes

import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.Screen
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.api.model.NoteResponse
import io.pelle.sak.core.uiText
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.URL
import io.pelle.sak.testing.english
import io.pelle.sak.testing.page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class NotesModelTest {
    private val now = Instant.parse("2026-11-02T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["POST /auth/login/link"] = HttpStatusCode.Accepted to ""
            answers["POST /auth/login/password"] = HttpStatusCode.OK to session("password-session", "admin")
            answers["GET /notes"] = HttpStatusCode.OK to page(note(2, "Buy milk", labels = listOf("shopping")), note(1, "Call the plumber", "pink"))
            answers["GET /labels"] = HttpStatusCode.OK to page("""{"id":1,"name":"work","createdAt":"2026-10-30T08:00:00.000Z"}""", """{"id":2,"name":"shopping","createdAt":"2026-10-30T08:00:00.000Z"}""")
            answers["GET /notes/settings"] = HttpStatusCode.OK to """{"discoverText":false}"""
            answers["GET /notes/labels"] = HttpStatusCode.OK to page("""{"id":2,"name":"shopping","createdAt":"2026-10-30T08:00:00.000Z"}""")
        }

    private fun session(
        token: String,
        email: String,
    ) = """{"sessionToken":"$token","email":"$email","expiresAt":"2026-12-02T08:00:00.000Z","roles":["admin"],"redirect":"/"}"""

    private fun note(
        id: Int,
        text: String,
        color: String = "yellow",
        labels: List<String> = emptyList(),
        photos: List<Int> = emptyList(),
        recordings: List<Int> = emptyList(),
    ) = """{"id":$id,"text":"$text","color":"$color","labels":[${labels.mapIndexed { i, l -> """{"id":${i + 1},"name":"$l","createdAt":"2026-10-30T08:00:00.000Z"}""" }.joinToString(",")}],""" +
        """"photos":[${photos.joinToString(",") { """{"id":$it,"url":"/api/v1/files/$it","thumbnailUrl":"/api/v1/files/$it/thumbnail"}""" }}],""" +
        """"recordings":[${recordings.joinToString(",") { """{"id":$it,"url":"/api/v1/files/$it","thumbnailUrl":"/api/v1/files/$it/thumbnail"}""" }}],""" +
        """"createdAt":"2026-11-02T07:00:00.000Z","updatedAt":null}"""

    // a recorder that records what the test hands it once the test stops it, or throws
    private class FakeRecorder : Recorder {
        var recording = CompletableDeferred<ByteArray?>()
        var fails = false

        override suspend fun record(): ByteArray? {
            if (fails) error("no microphone")
            return recording.await()
        }

        override fun stop() {
            if (!recording.isCompleted) recording.complete(null)
        }
    }

    private val recorder = FakeRecorder()

    private fun TestScope.model(store: MemorySessionStore = signedIn()): NotesModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        return NotesModel(store, backend::api, this, allowUrl = false, clock = Clock.fixed(now, ZoneOffset.UTC), recorder = recorder)
    }

    private fun signedIn() =
        MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org", token = "session", email = "pelle@pelle.io", expiresAt = Instant.parse("2026-12-02T08:00:00Z")))

    private fun NotesModel.notes() = screen.value as NotesScreen

    // the body of the last request to the method and path
    private fun body(key: String) = backend.requests.last { it.startsWith("$key ") }.removePrefix("$key ")

    @Test
    fun `the wall filters by the labels its notes have, a label goes with its last note, the suggestions are all`() =
        runTest {
            backend.answers["PUT /notes/2"] = HttpStatusCode.OK to note(2, "Buy milk")
            val model = model().also { it.start() }
            advanceUntilIdle()

            // the chips are the labels a note has, the suggestions every label
            assertEquals(listOf("shopping"), model.notes().chips)
            assertEquals(listOf("shopping", "work"), model.notes().labels)
            assertTrue(backend.requests.contains("GET /notes/labels"))

            // filtered by it, the chip stays while no note has the label any more, so it can be turned off
            model.filter("shopping")
            advanceUntilIdle()
            backend.answers["GET /notes/labels"] = HttpStatusCode.OK to page()
            model.removeLabel(2, "shopping")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), model.notes().used)
            assertEquals(listOf("shopping"), model.notes().chips)
            model.filter(null)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), model.notes().chips)
            assertEquals(listOf("shopping", "work"), model.notes().labels)
        }

    @Test
    fun `the login link it asks for names the notes app`() =
        runTest {
            val model = model(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))).also { it.start() }
            advanceUntilIdle()
            model.requestLink("pelle@pelle.io")
            advanceUntilIdle()
            assertTrue(body("POST /auth/login/link").startsWith("""{"email":"pelle@pelle.io","client":"notes-android","nonce":"""))
        }

    @Test
    fun `signed in it opens on the wall of all notes, every page, with the labels and the AI off`() =
        runTest {
            // a full first page, the second asked for at its offset; a short page is the last
            val first = (1..100).map { note(1000 - it, "Note $it") }
            backend.answersByQuery["GET /notes?offset=0&limit=100"] =
                HttpStatusCode.OK to """{"items":[${first.joinToString(",")}],"pagination":{"offset":0,"limit":100,"total":101}}"""
            backend.answersByQuery["GET /notes?offset=100&limit=100"] =
                HttpStatusCode.OK to """{"items":[${note(3, "Last page")}],"pagination":{"offset":100,"limit":100,"total":101}}"""
            val model = model(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))).also { it.start() }
            advanceUntilIdle()
            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()

            val screen = model.notes()
            assertFalse(screen.loading)
            assertFalse(screen.ai)
            // the second page asked for after the first, at its offset
            assertTrue(backend.queries.contains("GET /notes?offset=100&limit=100"))
            assertEquals(101, screen.notes.size)
            assertEquals(101, screen.allCount)
            assertEquals(listOf("work", "shopping").sorted(), screen.labels)
            // every user may read whether the AI is on
            assertTrue(backend.requests.contains("GET /notes/settings"))
        }

    @Test
    fun `the wall shows the notes of one label, and all again`() =
        runTest {
            val model = model().also { it.start() }
            advanceUntilIdle()
            assertEquals(listOf(2, 1), model.notes().notes.map { it.id })

            model.filter("shopping")
            advanceUntilIdle()
            assertEquals("shopping", model.notes().filter)
            assertTrue(backend.queries.last { it.startsWith("GET /notes?") }.contains("label=shopping"))

            model.filter(null)
            advanceUntilIdle()
            assertFalse(backend.queries.last { it.startsWith("GET /notes?") }.contains("label="))
        }

    @Test
    fun `a new note opens with the label shown, its text is saved once typing pauses`() =
        runTest {
            backend.answers["POST /notes"] = HttpStatusCode.Created to note(5, "", labels = listOf("shopping"))
            backend.answers["PUT /notes/5"] = HttpStatusCode.OK to note(5, "Bread", labels = listOf("shopping"))
            val model = model().also { it.start() }
            advanceUntilIdle()
            model.filter("shopping")
            advanceUntilIdle()

            model.create()
            advanceUntilIdle()
            assertEquals("""{"text":"","color":"yellow","labels":["shopping"],"photos":[],"recordings":[]}""", body("POST /notes"))
            assertEquals(5, model.notes().open)

            model.setText(5, "Bre")
            advanceTimeBy(SAVE_TEXT_AFTER_MS / 2)
            model.setText(5, "Bread")
            advanceTimeBy(SAVE_TEXT_AFTER_MS - 1)
            runCurrent()
            assertTrue(backend.requests.none { it.startsWith("PUT /notes/5") })
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("PUT /notes/5") })
            assertEquals("""{"text":"Bread","color":"yellow","labels":["shopping"],"photos":[],"recordings":[]}""", body("PUT /notes/5"))

            // back to the wall: the text typed is saved at once
            model.setText(5, "Bread and butter")
            model.open(null)
            advanceUntilIdle()
            assertNull(model.notes().open)
            assertTrue(body("PUT /notes/5").contains("Bread and butter"))
        }

    @Test
    fun `the color and the labels of a note are saved at once, a label normalized and at most ten`() =
        runTest {
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "blue")
            val model = model().also { it.start() }
            advanceUntilIdle()

            model.setColor(1, NoteResponse.Color.BLUE)
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""color":"blue""""))

            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "blue", labels = listOf("home work"))
            model.addLabel(1, "  Home   Work ")
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""labels":["home work"]"""))
            assertTrue("home work" in model.notes().labels)

            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "blue")
            model.removeLabel(1, "home work")
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""labels":[]"""))

            // the changes made before the save runs go in one save
            val saves = backend.requests.count { it.startsWith("PUT /notes/1") }
            (1..11).forEach { model.addLabel(1, "label $it") }
            assertEquals(MAX_LABELS, model.notes().note(1)!!.labels.size)
            advanceUntilIdle()
            assertEquals(saves + 1, backend.requests.count { it.startsWith("PUT /notes/1") })
            assertTrue(body("PUT /notes/1").contains(""""label 10""""))
            assertFalse(body("PUT /notes/1").contains(""""label 11""""))
        }

    @Test
    fun `a photo is stored first, then a new note is made with it or a note takes it`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":40,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/40","thumbnailUrl":"/api/v1/files/40/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["POST /notes"] = HttpStatusCode.Created to note(6, "", photos = listOf(40))
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "pink", photos = listOf(40))
            val model = model().also { it.start() }
            advanceUntilIdle()

            model.createWithPhoto { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertEquals("jpeg", body("POST /files"))
            assertTrue(body("POST /notes").contains(""""photos":[40]"""))
            assertEquals(6, model.notes().open)

            model.addPhoto(1) { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""photos":[40]"""))

            model.addPhoto(1) { null }
            advanceUntilIdle()
            assertEquals("The photo could not be read.", model.notes().error.english())

            model.removePhoto(1, 40)
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""photos":[]"""))
        }

    @Test
    fun `a recording is stored as a wav and attached, for a new note too, stopped before it recorded nothing is kept`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":50,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/50","thumbnailUrl":"/api/v1/files/50/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "pink", recordings = listOf(50))
            backend.answers["POST /notes"] = HttpStatusCode.Created to note(7, "", recordings = listOf(50))
            val model = model().also { it.start() }
            advanceUntilIdle()

            model.record(RecordingFor.Note(1))
            runCurrent()
            assertEquals(RecordingFor.Note(1), model.notes().recording)
            recorder.recording.complete("RIFFwav".toByteArray())
            advanceUntilIdle()
            assertNull(model.notes().recording)
            assertEquals("RIFFwav", body("POST /files"))
            assertTrue(body("PUT /notes/1").contains(""""recordings":[50]"""))

            recorder.recording = CompletableDeferred()
            model.record(RecordingFor.New)
            runCurrent()
            recorder.recording.complete("RIFFwav".toByteArray())
            advanceUntilIdle()
            assertTrue(body("POST /notes").contains(""""recordings":[50]"""))
            assertEquals(7, model.notes().open)

            // a second record stops the recording, which was not long enough to keep
            recorder.recording = CompletableDeferred()
            val files = backend.requests.count { it.startsWith("POST /files") }
            model.record(RecordingFor.New)
            runCurrent()
            model.record(RecordingFor.New)
            advanceUntilIdle()
            assertNull(model.notes().recording)
            assertEquals(files, backend.requests.count { it.startsWith("POST /files") })

            recorder.fails = true
            model.record(RecordingFor.New)
            advanceUntilIdle()
            assertEquals("The microphone could not be used.", model.notes().error.english())
            model.microphoneDenied()
            assertTrue(model.notes().error.english()!!.contains("may not use the microphone"))
        }

    @Test
    fun `the AI discovers the text of a photo or a recording only when it is turned on, the text goes into the note`() =
        runTest {
            backend.answers["GET /notes"] = HttpStatusCode.OK to page(note(1, "Receipt", photos = listOf(40), recordings = listOf(50)))
            val model = model().also { it.start() }
            advanceUntilIdle()

            // off: nothing goes to the AI
            model.discover(1, 40)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.contains("discover") })

            backend.answers["GET /notes/settings"] = HttpStatusCode.OK to """{"discoverText":true}"""
            model.refresh()
            advanceUntilIdle()
            assertTrue(model.notes().ai)

            backend.answers["POST /files/40/discover/text"] = HttpStatusCode.OK to """{"text":"Milk 1.20"}"""
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Receipt\n\nMilk 1.20", photos = listOf(40), recordings = listOf(50))
            model.discover(1, 40)
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""text":"Receipt\n\nMilk 1.20""""))
            assertTrue(model.notes().discovering.isEmpty())

            backend.answers["POST /files/50/discover/text"] = HttpStatusCode.OK to """{"text":""}"""
            model.discover(1, 50)
            advanceUntilIdle()
            assertEquals("There is no text in the recording.", model.notes().error.english())

            backend.answers["POST /files/40/discover/text"] =
                HttpStatusCode.Conflict to """{"errors":[{"code":"files.ai_off","message":"The AI features are turned off."}]}"""
            model.discover(1, 40)
            advanceUntilIdle()
            assertEquals("The AI features are turned off.", model.notes().error.english())
        }

    @Test
    fun `the AI is off when the backend can not say, without an error`() =
        runTest {
            backend.answers["GET /notes/settings"] = HttpStatusCode.InternalServerError to ""
            val model = model().also { it.start() }
            advanceUntilIdle()
            assertFalse(model.notes().ai)
            assertNull(model.notes().error)
        }

    @Test
    fun `a note is deleted after asking, its screen closes`() =
        runTest {
            backend.answers["DELETE /notes/1"] = HttpStatusCode.NoContent to ""
            val model = model().also { it.start() }
            advanceUntilIdle()
            model.open(1)
            model.askDelete(1)
            assertEquals(1, model.notes().deleting)
            model.dismissDelete()
            assertNull(model.notes().deleting)

            model.askDelete(1)
            model.delete(1)
            advanceUntilIdle()
            assertEquals(listOf(2), model.notes().notes.map { it.id })
            assertNull(model.notes().open)
            assertNull(model.notes().deleting)

            backend.answers["DELETE /notes/2"] = HttpStatusCode.InternalServerError to ""
            model.delete(2)
            advanceUntilIdle()
            assertEquals("The note could not be deleted.", model.notes().error.english())
            assertEquals(listOf(2), model.notes().notes.map { it.id })
        }

    @Test
    fun `a session that ended leads to the sign-in`() =
        runTest {
            backend.answers["GET /notes"] = HttpStatusCode.Unauthorized to ""
            val store = signedIn()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            val signIn = model.screen.value as Screen.SignIn
            assertEquals(uiText(CoreR.string.error_session_ended), signIn.error)
            assertNull(store.load().token)
        }

    // the note of a save as the backend answers it: the note the request sent, with its id
    private fun saved(
        id: Int,
        body: String,
    ) = """{"id":$id,""" + body.removePrefix("{").replace(Regex(""""labels":\[([^\]]*)\]""")) { match ->
        val names = Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
        """"labels":[${names.mapIndexed { i, l -> """{"id":${i + 1},"name":"$l","createdAt":"2026-10-30T08:00:00.000Z"}""" }.joinToString(",")}]"""
    }.replace(Regex(""""(photos|recordings)":\[([0-9,]*)\]""")) { match ->
        val ids = match.groupValues[2].split(",").filter { it.isNotBlank() }
        """"${match.groupValues[1]}":[${ids.joinToString(",") { """{"id":$it,"url":"/api/v1/files/$it","thumbnailUrl":"/api/v1/files/$it/thumbnail"}""" }}]"""
    }.removeSuffix("}") + ""","createdAt":"2026-11-02T07:00:00.000Z","updatedAt":null}"""

    private fun TestScope.gated(): Pair<GatedBackend, NotesModel> {
        val gated = GatedBackend(StandardTestDispatcher(testScheduler))
        gated.answers["GET /notes"] = { HttpStatusCode.OK to page(note(2, "Buy milk"), note(1, "Call the plumber", "pink")) }
        gated.answers["GET /labels"] = { HttpStatusCode.OK to page() }
        gated.answers["GET /notes/settings"] = { HttpStatusCode.OK to """{"discoverText":false}""" }
        gated.answers["PUT /notes/1"] = { body -> HttpStatusCode.OK to saved(1, body) }
        gated.answers["PUT /notes/2"] = { body -> HttpStatusCode.OK to saved(2, body) }
        val model = NotesModel(signedIn(), { url, token -> gated.api(url, token) }, this, allowUrl = false, clock = Clock.fixed(now, ZoneOffset.UTC), recorder = recorder)
        return gated to model
    }

    @Test
    fun `the saves of a note go one at a time, a change during a save sends the note once more after it`() =
        runTest {
            val (gated, model) = gated()
            model.start()
            advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            gated.gates["PUT /notes/1"] = gate
            model.setColor(1, NoteResponse.Color.BLUE)
            runCurrent()
            assertEquals(1, gated.requests.count { it.startsWith("PUT /notes/1") })
            // the first save waits for its answer: the second change is not sent beside it
            model.setColor(1, NoteResponse.Color.GREEN)
            model.addLabel(1, "home")
            runCurrent()
            assertEquals(1, gated.requests.count { it.startsWith("PUT /notes/1") })

            gate.complete(Unit)
            advanceUntilIdle()
            val puts = gated.requests.filter { it.startsWith("PUT /notes/1") }
            assertEquals(2, puts.size)
            assertTrue(puts[0].contains(""""color":"blue""""))
            assertTrue(puts[1].contains(""""color":"green"""") && puts[1].contains(""""labels":["home"]"""))
            // the answer of the older save did not take the place of the newer note
            assertEquals(NoteResponse.Color.GREEN, model.notes().note(1)!!.color)
            assertEquals(listOf("home"), model.notes().note(1)!!.labels.map { it.name })
        }

    @Test
    fun `a load keeps what changed while it ran, a note typed, created or deleted`() =
        runTest {
            val (gated, model) = gated()
            gated.answers["POST /notes"] = { HttpStatusCode.Created to note(9, "") }
            gated.answers["DELETE /notes/2"] = { HttpStatusCode.NoContent to "" }
            model.start()
            advanceUntilIdle()

            // the load answers the notes as they were before the changes
            val load = CompletableDeferred<Unit>()
            gated.gates["GET /notes"] = load
            model.refresh()
            runCurrent()
            model.setText(1, "Call the plumber at 9")
            model.delete(2)
            model.create()
            advanceTimeBy(SAVE_TEXT_AFTER_MS + 1)
            runCurrent()
            assertEquals(9, model.notes().open)

            load.complete(Unit)
            advanceUntilIdle()
            val screen = model.notes()
            assertEquals(listOf(9, 1), screen.notes.map { it.id })
            assertEquals("Call the plumber at 9", screen.note(1)!!.text)
            assertEquals(9, screen.open)
        }

    @Test
    fun `the limits of the backend hold in the app, a change it refuses goes back`() =
        runTest {
            val full = (1..MAX_PHOTOS).toList()
            backend.answers["GET /notes"] = HttpStatusCode.OK to page(note(1, "Full", photos = full, recordings = (100 until 100 + MAX_RECORDINGS).toList()), note(2, "Short"))
            val model = model().also { it.start() }
            advanceUntilIdle()

            var read = false
            model.addPhoto(1) {
                read = true
                "jpeg".toByteArray()
            }
            advanceUntilIdle()
            assertTrue(read)
            assertTrue(backend.requests.none { it.startsWith("POST /files") })
            assertEquals("A note has at most 20 photos.", model.notes().error.english())

            model.record(RecordingFor.Note(1))
            assertNull(model.notes().recording)
            assertEquals("A note has at most 20 recordings.", model.notes().error.english())

            model.addLabel(2, "x".repeat(MAX_LABEL_LENGTH + 1))
            assertEquals("A label has at most 64 characters.", model.notes().error.english())
            assertTrue(model.notes().note(2)!!.labels.isEmpty())

            model.setText(2, "y".repeat(MAX_TEXT_LENGTH + 5))
            assertEquals(MAX_TEXT_LENGTH, model.notes().note(2)!!.text.length)

            // refused by the backend: the note goes back as it was stored, the error says why
            backend.answers["PUT /notes/2"] =
                HttpStatusCode.BadRequest to """{"errors":[{"code":"notes.text_too_long","message":"The text must have at most 10000 characters."}]}"""
            advanceUntilIdle()
            assertEquals("Short", model.notes().note(2)!!.text)
            assertEquals("The text must have at most 10000 characters.", model.notes().error.english())
        }

    @Test
    fun `a photo or a recording taken off comes back with undo, the backend learns of it once the time is up, UX-4 point 2`() =
        runTest {
            backend.answers["GET /notes"] = HttpStatusCode.OK to page(note(1, "Receipt", photos = listOf(40, 41), recordings = listOf(50)))
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Receipt", photos = listOf(40, 41))
            val model = model().also { it.start() }
            advanceUntilIdle()

            model.removePhoto(1, 40)
            assertEquals(listOf(41), model.notes().note(1)!!.photos.map { it.id })
            assertEquals(1, model.notes().removal!!.number)
            advanceTimeBy(UNDO_REMOVE_MS - 1)
            runCurrent()
            model.undoRemoval()
            advanceUntilIdle()
            assertEquals(listOf(40, 41), model.notes().note(1)!!.photos.map { it.id })
            assertNull(model.notes().removal)
            assertTrue(backend.requests.none { it.startsWith("PUT /notes/1") })

            // a second removal sends the first at once; the time up, the second goes too
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Receipt", photos = listOf(40))
            model.removeRecording(1, 50)
            model.removePhoto(1, 41)
            runCurrent()
            assertTrue(body("PUT /notes/1").contains(""""recordings":[]"""))
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""photos":[40]"""))
            assertNull(model.notes().removal)
        }

    @Test
    fun `a photo or a recording that is not stored says so, a session that ends during a save leads to the sign-in`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.InternalServerError to ""
            val store = signedIn()
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            model.addPhoto(1) { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertEquals("The photo could not be stored.", model.notes().error.english())
            assertTrue(model.notes().uploading.isEmpty())
            assertTrue(backend.requests.none { it.startsWith("PUT /notes/1") })

            model.record(RecordingFor.Note(1))
            runCurrent()
            recorder.recording.complete("RIFFwav".toByteArray())
            advanceUntilIdle()
            assertEquals("The recording could not be stored.", model.notes().error.english())

            backend.answers["PUT /notes/1"] = HttpStatusCode.Unauthorized to ""
            model.setColor(1, NoteResponse.Color.GREEN)
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
            assertNull(store.load().token)
        }

    @Test
    fun `a session that ends during a recording leads to the sign-in, the recording stops`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Unauthorized to ""
            val model = model().also { it.start() }
            advanceUntilIdle()
            model.record(RecordingFor.New)
            runCurrent()
            recorder.recording.complete("RIFFwav".toByteArray())
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
        }

    @Test
    fun `a photo handed over before the notes are there waits for them`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":40,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/40","thumbnailUrl":"/api/v1/files/40/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "pink", photos = listOf(40))
            val model = model()
            // android ended the app behind the camera: the model starts again with the photo
            model.addPhoto(1) { "jpeg".toByteArray() }
            assertTrue(backend.requests.none { it.startsWith("POST /files") })
            model.start()
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""photos":[40]"""))
        }

    private val photoFile = """{"id":40,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/40","thumbnailUrl":"/api/v1/files/40/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""

    private fun shares(count: Int): List<suspend () -> ByteArray?> = List(count) { { "jpeg".toByteArray() } }

    @Test
    fun `photos another app shares become one new note with its text, at most MAX_PHOTOS of them`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to photoFile
            backend.answers["POST /notes"] = HttpStatusCode.Created to note(6, "Look at this", photos = listOf(40))
            val model = model().also { it.start() }
            advanceUntilIdle()

            model.createWithPhotos(shares(2), "  Look at this \n")
            advanceUntilIdle()
            assertEquals(2, backend.requests.count { it.startsWith("POST /files") })
            assertEquals(1, backend.requests.count { it.startsWith("POST /notes") })
            assertTrue(body("POST /notes").contains(""""text":"Look at this""""))
            assertEquals(6, model.notes().open)
            assertNull(model.notes().error)

            // more than a note takes: the first MAX_PHOTOS are stored, the others are still read
            backend.requests.clear()
            var read = 0
            model.createWithPhotos(List(MAX_PHOTOS + 2) { { read++; "jpeg".toByteArray() } })
            advanceUntilIdle()
            assertEquals(MAX_PHOTOS + 2, read)
            assertEquals(MAX_PHOTOS, backend.requests.count { it.startsWith("POST /files") })
            assertEquals("A note has at most 20 photos.", model.notes().error.english())
        }

    @Test
    fun `a shared photo that can not be read is left out, without any a note is made of the text only`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to photoFile
            backend.answers["POST /notes"] = HttpStatusCode.Created to note(6, "", photos = listOf(40))
            val model = model().also { it.start() }
            advanceUntilIdle()

            model.createWithPhotos(listOf({ null }, { "jpeg".toByteArray() }))
            advanceUntilIdle()
            assertTrue(body("POST /notes").contains(""""photos":[40]"""))
            assertEquals("The photo could not be read.", model.notes().error.english())

            backend.requests.clear()
            model.createWithPhotos(listOf { null })
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("POST /notes") })
            assertFalse(model.notes().creating)

            model.createWithPhotos(listOf { null }, "Caption")
            advanceUntilIdle()
            assertTrue(body("POST /notes").contains(""""text":"Caption""""))
            assertTrue(body("POST /notes").contains(""""photos":[]"""))

            // a text longer than a note takes is cut, the app says so
            model.createWithPhotos(emptyList(), "a".repeat(MAX_TEXT_LENGTH + 1))
            advanceUntilIdle()
            assertTrue(body("POST /notes").contains(""""text":"${"a".repeat(MAX_TEXT_LENGTH)}""""))
            assertEquals("A note has at most 10000 characters.", model.notes().error.english())
        }

    @Test
    fun `photos shared when the app starts wait for the notes, the ones shared while a note is made for it`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to photoFile
            backend.answers["POST /notes"] = HttpStatusCode.Created to note(6, "", photos = listOf(40))
            val model = model()
            // the app started for the share: the session is not known yet
            model.createWithPhotos(shares(1), "first")
            assertTrue(backend.requests.none { it.startsWith("POST /files") })
            model.start()
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("POST /notes") })

            // a second share while the first note is still being made is made into a note after it
            val first = CompletableDeferred<ByteArray?>()
            model.createWithPhotos(listOf { first.await() }, "second")
            runCurrent()
            assertTrue(model.notes().creating)
            model.createWithPhotos(shares(1), "third")
            first.complete("jpeg".toByteArray())
            advanceUntilIdle()
            val made = backend.requests.filter { it.startsWith("POST /notes") }
            assertEquals(3, made.size)
            assertTrue(made[1].contains(""""text":"second""""))
            assertTrue(made[2].contains(""""text":"third""""))
        }

    @Test
    fun `photos shared without a session go, the sign-in says so`() =
        runTest {
            val model = model(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))).also { it.start() }
            advanceUntilIdle()
            var read = false
            model.createWithPhotos(listOf { read = true; "jpeg".toByteArray() })
            advanceUntilIdle()
            assertTrue(read)
            assertTrue(backend.requests.none { it.startsWith("POST /files") })
            assertEquals("Sign in first, then share the photos with the app again.", (model.screen.value as Screen.SignIn).error.english())

            // shared when the app starts, without a session: they wait until it is known there is none
            val starting = model(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org")))
            var waited = false
            starting.createWithPhotos(listOf { waited = true; "jpeg".toByteArray() })
            starting.start()
            advanceUntilIdle()
            assertTrue(waited)
            assertEquals("Sign in first, then share the photos with the app again.", (starting.screen.value as Screen.SignIn).error.english())

            val fresh = model(MemorySessionStore()).also { it.start() }
            advanceUntilIdle()
            fresh.createWithPhotos(shares(1))
            assertEquals("Sign in first, then share the photos with the app again.", (fresh.screen.value as Screen.Deployment).error.english())
        }

    @Test
    fun `the text of a share is trimmed and cut at MAX_TEXT_LENGTH, an emoji not in the middle`() {
        assertEquals("caption", sharedText("  caption\n"))
        assertEquals("a".repeat(MAX_TEXT_LENGTH), sharedText("a".repeat(MAX_TEXT_LENGTH + 5)))
        assertEquals("a".repeat(MAX_TEXT_LENGTH - 1), sharedText("a".repeat(MAX_TEXT_LENGTH - 1) + "😀"))
    }

    @Test
    fun `the notes load again on coming to the front only when the last load is older than half a minute`() =
        runTest {
            val clock = MovableClock(now)
            backend.dispatcher = StandardTestDispatcher(testScheduler)
            val model = NotesModel(signedIn(), backend::api, this, allowUrl = false, clock = clock, recorder = recorder).also { it.start() }
            advanceUntilIdle()
            val loads = { backend.requests.count { it == "GET /notes" } }
            val first = loads()
            model.resume()
            advanceUntilIdle()
            assertEquals(first, loads())
            clock.now = now.plusMillis(RELOAD_AFTER_MS)
            model.resume()
            advanceUntilIdle()
            assertEquals(first + 1, loads())
        }

    @Test
    fun `a note is deleted once also when asked twice, going to the background stops the recording and saves the text`() =
        runTest {
            backend.answers["DELETE /notes/2"] = HttpStatusCode.NoContent to ""
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Typed")
            val model = model().also { it.start() }
            advanceUntilIdle()
            model.delete(2)
            assertTrue(2 in model.notes().deletingNow)
            model.delete(2)
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("DELETE /notes/2") })
            assertTrue(model.notes().deletingNow.isEmpty())

            model.setText(1, "Typed")
            model.record(RecordingFor.Note(1))
            runCurrent()
            model.pause()
            advanceUntilIdle()
            assertNull(model.notes().recording)
            assertTrue(body("PUT /notes/1").contains(""""text":"Typed""""))
        }

    @Test
    fun `a recording of the longest time says it stopped there`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":50,"contentType":"audio/wav","size":1,"url":"/api/v1/files/50","thumbnailUrl":"/api/v1/files/50/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["POST /notes"] = HttpStatusCode.Created to note(7, "", recordings = listOf(50))
            val model = model().also { it.start() }
            advanceUntilIdle()
            model.record(RecordingFor.New)
            runCurrent()
            assertEquals(now, model.notes().recordingSince)
            recorder.recording.complete(wav(ByteArray(RECORDING_RATE * 2 * MAX_RECORDING_SECONDS)))
            advanceUntilIdle()
            assertEquals("The recording stopped after 5 minutes, the longest a recording is.", model.notes().notice.english())
            assertNull(model.notes().recordingSince)
        }

    // a clock the test moves
    private class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    @Test
    fun `a recording is a wav of 16 kHz mono 16 bit`() {
        val samples = byteArrayOf(1, 0, 2, 0)
        val wav = wav(samples)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + samples.size, header.getInt(4))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(RECORDING_RATE, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(samples.size, header.getInt(40))
        assertArrayEquals(samples, wav.copyOfRange(44, wav.size))
    }
}
