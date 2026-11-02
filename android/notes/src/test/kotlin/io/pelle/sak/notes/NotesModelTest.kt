package io.pelle.sak.notes

import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.Screen
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
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
            answers["GET /settings/notes"] = HttpStatusCode.OK to """{"ai":false}"""
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
        MemorySessionStore(Stored(apiUrl = URL, deployment = "family", token = "session", email = "pelle@pelle.io", expiresAt = Instant.parse("2026-12-02T08:00:00Z")))

    private fun NotesModel.notes() = screen.value as NotesScreen

    // the body of the last request to the method and path
    private fun body(key: String) = backend.requests.last { it.startsWith("$key ") }.removePrefix("$key ")

    @Test
    fun `the login link it asks for names the notes app`() =
        runTest {
            val model = model(MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))).also { it.start() }
            advanceUntilIdle()
            model.requestLink("pelle@pelle.io")
            advanceUntilIdle()
            assertEquals("""{"email":"pelle@pelle.io","client":"notes-android"}""", body("POST /auth/login/link"))
        }

    @Test
    fun `signed in it opens on the wall of all notes, every page, with the labels and the AI off`() =
        runTest {
            backend.answers["GET /notes"] =
                HttpStatusCode.OK to """{"items":[${note(3, "First page")}],"pagination":{"offset":0,"limit":100,"total":2}}"""
            val model = model(MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))).also { it.start() }
            advanceUntilIdle()
            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()

            val screen = model.notes()
            assertEquals("family", screen.deployment)
            assertFalse(screen.loading)
            assertFalse(screen.ai)
            // the second page asked for after the first, at its offset
            assertTrue(backend.queries.any { it.startsWith("GET /notes?") && it.contains("offset=1") })
            assertEquals(listOf("work", "shopping").sorted(), screen.labels)
            assertTrue(backend.queries.contains("GET /settings/notes?effective=true"))
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

            model.setColor(1, "blue")
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""color":"blue""""))

            model.setColor(1, "purple")
            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "blue", labels = listOf("home work"))
            model.addLabel(1, "  Home   Work ")
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""labels":["home work"]"""))
            assertFalse(backend.requests.any { it.contains("purple") })
            assertTrue("home work" in model.notes().labels)

            backend.answers["PUT /notes/1"] = HttpStatusCode.OK to note(1, "Call the plumber", "blue")
            model.removeLabel(1, "home work")
            advanceUntilIdle()
            assertTrue(body("PUT /notes/1").contains(""""labels":[]"""))

            val saves = backend.requests.count { it.startsWith("PUT /notes/1") }
            (1..11).forEach { model.addLabel(1, "label $it") }
            assertEquals(MAX_LABELS, model.notes().note(1)!!.labels.size)
            advanceUntilIdle()
            assertEquals(saves + MAX_LABELS, backend.requests.count { it.startsWith("PUT /notes/1") })
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

            backend.answers["GET /settings/notes"] = HttpStatusCode.OK to """{"ai":true}"""
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
    fun `the AI is off when the settings can not be read, by a user who is no admin`() =
        runTest {
            backend.answers["GET /settings/notes"] = HttpStatusCode.Forbidden to ""
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
