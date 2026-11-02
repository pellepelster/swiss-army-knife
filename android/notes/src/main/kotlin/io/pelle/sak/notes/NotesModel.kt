package io.pelle.sak.notes

import io.ktor.http.ContentType
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.AppUpdate
import io.pelle.sak.core.update.Updater
import io.pelle.sak.notes.api.LabelResponse
import io.pelle.sak.notes.api.NoteFileResponse
import io.pelle.sak.notes.api.NoteRequest
import io.pelle.sak.notes.api.NoteResponse
import io.pelle.sak.notes.api.NotesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock

// the colors of a note, the first is the one of a new note (NoteColor of sak-backend)
val NOTE_COLORS = listOf("yellow", "pink", "blue", "green", "orange")

// the most labels a note has, the backend takes no more
const val MAX_LABELS = 10

// how long typing pauses before the text is saved, as the web app
const val SAVE_TEXT_AFTER_MS = 600L

// the photos and recordings fetched that are kept, the ones used last
private const val MAX_FILES = 64

// labels are compared without case and surrounding blanks, `Work ` and `work` are one label; the
// backend stores them the same way
fun normalizeLabel(label: String): String = label.trim().replace(Regex("\\s+"), " ").lowercase()

// records the microphone as a wav (16 kHz mono 16 bit, what the backend takes and the AI reads) until
// stop() or its longest recording; null when it was stopped before it recorded. Throws when the
// microphone can not be used
interface Recorder {
    suspend fun record(): ByteArray?

    fun stop()
}

// what is being recorded for: a new note or a note
sealed interface RecordingFor {
    data object New : RecordingFor

    data class Note(
        val id: Int,
    ) : RecordingFor
}

// the notes of the signed in user once signed in: the wall of their notes, newest first, of the label
// filtered by or all (NOTES-1: the lists are not shown, the app opens on the notes, LIST-3)
data class NotesScreen(
    override val deployment: String,
    override val user: String,
    override val update: AppUpdate? = null,
    override val updateNotice: UiText? = null,
    val notes: List<NoteResponse> = emptyList(),
    // the labels to choose from: the ones of all apps and of the notes, sorted
    val labels: List<String> = emptyList(),
    // the label the wall shows the notes of, null for all
    val filter: String? = null,
    val loading: Boolean = true,
    val error: UiText? = null,
    // whether the notes offer the AI, off until the settings of the notes say otherwise
    val ai: Boolean = false,
    // the note open in its own screen, to write and change it
    val open: Int? = null,
    // the note whose delete asks first (UX-4.1)
    val deleting: Int? = null,
    val recording: RecordingFor? = null,
    // a new note being created, e.g. from a photo
    val creating: Boolean = false,
    // the notes a photo or a recording is being stored for
    val uploading: Set<Int> = emptySet(),
    // the photos and recordings whose text is being discovered
    val discovering: Set<Int> = emptySet(),
) : AppScreen {
    override fun withUser(user: String) = copy(user = user)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)

    fun note(id: Int?) = notes.firstOrNull { it.id == id }
}

// the logic of the notes app, without android: the notes are kept by the backend, a change shows at
// once and is saved in the background, the text once typing pauses. Photos are scaled down and
// recordings made by the ui and the recorder, uploaded to the file storage and attached. The AI
// discovers the text of a photo or a recording only when an admin turned it on in the settings of the
// notes (the privacy rule: its consent names OpenRouter); the backend refuses it otherwise
class NotesModel(
    store: SessionStore,
    apiOf: (url: String, token: String?) -> NotesApi,
    scope: CoroutineScope,
    allowUrl: Boolean,
    clock: Clock = Clock.systemUTC(),
    appVersion: String = "latest",
    updater: Updater = Updater { error("no updater") },
    private val recorder: Recorder = NO_RECORDER,
) : SakModel<NotesApi>(store, apiOf, scope, allowUrl, clock, appVersion, updater) {
    // the text typed and not saved yet, by note: its save after the pause
    private val pendingText = mutableMapOf<Int, Job>()

    // the changes made here, by note: the answer of a save of an older one does not take the place of
    // a newer one
    private val versions = mutableMapOf<Int, Int>()

    private var loading: Job? = null

    // the photos and recordings fetched, by their file and whether it is the thumbnail
    private val files =
        object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_FILES
        }

    override fun showApp(stored: Stored) {
        state.value = NotesScreen(stored.deployment ?: stored.apiUrl.orEmpty(), stored.email.orEmpty())
        load()
    }

    // what is typed is sent before the session goes
    override suspend fun signingOut() = flush()

    override fun sessionEnding() {
        pendingText.values.forEach { it.cancel() }
        pendingText.clear()
        recorder.stop()
    }

    override fun clearSession() {
        files.clear()
        versions.clear()
    }

    // the notes of the filter, every page, the labels and whether the AI is offered; the notes with a
    // change not saved yet stay as they are here, the backend may not know of it
    fun load() {
        notes { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading =
            scope.launch {
                val filter = notesScreen()?.filter
                val loaded =
                    request(R.string.notes_error_not_loaded, ::failed) { api ->
                        val all = mutableListOf<NoteResponse>()
                        while (true) {
                            val page = api.notes(filter, all.size, SakApi.PAGE_LIMIT)
                            all += page.items
                            if (page.items.isEmpty() || all.size >= page.pagination.total) break
                        }
                        Triple(all, runCatching { api.labels().map { it.name } }.getOrDefault(emptyList()), api.aiEnabled())
                    }
                if (loaded == null) {
                    notes { it.copy(loading = false) }
                    return@launch
                }
                val (all, shared, ai) = loaded
                notes { screen ->
                    val changed = screen.notes.filter { it.id in pendingText }.associateBy { it.id }
                    screen.copy(
                        notes = all.map { changed[it.id] ?: it },
                        labels = (shared + all.flatMap { note -> note.labels.map { it.name } }).distinct().sorted(),
                        ai = ai,
                        loading = false,
                    )
                }
            }
    }

    // the app came to the front again: the notes may have changed elsewhere (no stream of changes)
    fun resume() {
        if (notesScreen() != null) load()
    }

    // the app goes to the background: what is typed is saved now
    fun pause() {
        scope.launch { flush() }
    }

    fun refresh() = load()

    // the wall shows the notes of the label, or all with null
    fun filter(label: String?) {
        val screen = notesScreen() ?: return
        if (screen.filter == label) return
        notes { it.copy(filter = label, notes = emptyList()) }
        load()
    }

    fun dismissError() = notes { it.copy(error = null) }

    // a note in its own screen, or back to the wall, its text saved first
    fun open(id: Int?) {
        val previous = notesScreen()?.open
        notes { it.copy(open = id) }
        if (previous != null && previous != id) scope.launch { flush(previous) }
    }

    // a new empty note, labelled with the label the wall shows, open to be written
    fun create() = create(photos = emptyList(), recordings = emptyList())

    // a new note with the photo; read is the photo, scaled down already, null when it could not be read
    fun createWithPhoto(read: suspend () -> ByteArray?) {
        if (notesScreen()?.creating != false) return
        notes { it.copy(creating = true, error = null) }
        scope.launch {
            val fileId = uploadPhoto(read)
            if (fileId == null) {
                notes { it.copy(creating = false) }
                return@launch
            }
            createNow(photos = listOf(fileId), recordings = emptyList())
        }
    }

    fun setText(
        id: Int,
        text: String,
    ) {
        if (!change(id) { if (it.text == text) it else it.copy(text = text) }) return
        pendingText.remove(id)?.cancel()
        pendingText[id] =
            scope.launch {
                delay(SAVE_TEXT_AFTER_MS)
                pendingText.remove(id)
                save(id)
            }
    }

    fun setColor(
        id: Int,
        color: String,
    ) {
        if (color in NOTE_COLORS && change(id) { if (it.color == color) it else it.copy(color = color) }) saveNow(id)
    }

    // a blank label, one the note has already or one more than MAX_LABELS changes nothing
    fun addLabel(
        id: Int,
        label: String,
    ) {
        val name = normalizeLabel(label)
        if (name.isEmpty()) return
        val changed =
            change(id) { note ->
                if (note.labels.any { it.name == name } || note.labels.size >= MAX_LABELS) {
                    note
                } else {
                    note.copy(labels = (note.labels + LabelResponse(0, name)).sortedBy { it.name })
                }
            }
        if (changed) {
            notes { it.copy(labels = (it.labels + name).distinct().sorted()) }
            saveNow(id)
        }
    }

    fun removeLabel(
        id: Int,
        label: String,
    ) {
        if (change(id) { note -> if (note.labels.none { it.name == label }) note else note.copy(labels = note.labels.filter { it.name != label }) }) saveNow(id)
    }

    // the photo is attached once it is stored; read is the photo, scaled down already, null when it
    // could not be read
    fun addPhoto(
        id: Int,
        read: suspend () -> ByteArray?,
    ) {
        notes { it.copy(uploading = it.uploading + id, error = null) }
        scope.launch {
            val fileId = uploadPhoto(read)
            notes { it.copy(uploading = it.uploading - id) }
            if (fileId != null && change(id) { if (it.photos.any { photo -> photo.id == fileId }) it else it.copy(photos = it.photos + NoteFileResponse(fileId)) }) {
                saveNow(id)
            }
        }
    }

    // the file stays in the storage, other notes may show it too (NOTES-2)
    fun removePhoto(
        id: Int,
        fileId: Int,
    ) {
        if (change(id) { note -> note.copy(photos = note.photos.filter { it.id != fileId }) }) saveNow(id)
    }

    fun removeRecording(
        id: Int,
        fileId: Int,
    ) {
        if (change(id) { note -> note.copy(recordings = note.recordings.filter { it.id != fileId }) }) saveNow(id)
    }

    // starts recording for a note or a new note, a second call stops it; the recording is stored and
    // attached, a new note is made with it
    fun record(target: RecordingFor) {
        val screen = notesScreen() ?: return
        if (screen.recording != null) {
            recorder.stop()
            return
        }
        notes { it.copy(recording = target, error = null) }
        scope.launch {
            val wav =
                try {
                    recorder.record()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    notes { it.copy(error = uiText(R.string.notes_error_microphone)) }
                    null
                } finally {
                    notes { it.copy(recording = null) }
                }
            // stopped before it recorded, there is nothing to keep
            if (wav == null) return@launch
            val id = (target as? RecordingFor.Note)?.id
            if (id != null) notes { it.copy(uploading = it.uploading + id) } else notes { it.copy(creating = true) }
            val fileId =
                request(R.string.notes_error_recording_not_stored, ::failed) { api ->
                    api.upload(wav, WAV, R.string.notes_error_recording_not_stored)
                }
            if (id == null) {
                if (fileId == null) notes { it.copy(creating = false) } else createNow(photos = emptyList(), recordings = listOf(fileId))
                return@launch
            }
            notes { it.copy(uploading = it.uploading - id) }
            if (fileId != null &&
                change(id) { if (it.recordings.any { recording -> recording.id == fileId }) it else it.copy(recordings = it.recordings + NoteFileResponse(fileId)) }
            ) {
                saveNow(id)
            }
        }
    }

    fun stopRecording() = recorder.stop()

    // the app may not use the microphone, the user did not allow it
    fun microphoneDenied() = notes { it.copy(error = uiText(R.string.notes_error_microphone_denied)) }

    fun photoNotRead() = notes { it.copy(error = uiText(R.string.notes_error_photo_not_read)) }

    // the text the AI discovers in a photo or a recording of the note is added to its text (NOTES-5);
    // only with the AI turned on, as the web app
    fun discover(
        id: Int,
        fileId: Int,
    ) {
        val screen = notesScreen() ?: return
        if (!screen.ai || fileId in screen.discovering) return
        val recording = screen.note(id)?.recordings?.any { it.id == fileId } == true
        notes { it.copy(discovering = it.discovering + fileId, error = null) }
        scope.launch {
            val text = request(R.string.notes_error_not_discovered, ::failed) { it.discoverText(fileId) }
            notes { it.copy(discovering = it.discovering - fileId) }
            when {
                text == null -> {}
                text.isBlank() ->
                    notes { it.copy(error = uiText(if (recording) R.string.notes_error_no_text_in_recording else R.string.notes_error_no_text_in_photo)) }
                change(id) { note -> note.copy(text = if (note.text.isBlank()) text else "${note.text.trimEnd()}\n\n$text") } -> {
                    pendingText.remove(id)?.cancel()
                    saveNow(id)
                }
            }
        }
    }

    fun askDelete(id: Int) = notes { it.copy(deleting = id) }

    fun dismissDelete() = notes { it.copy(deleting = null) }

    // the note goes for good, its files stay in the storage (NOTES-2); back to the wall
    fun delete(id: Int) {
        pendingText.remove(id)?.cancel()
        notes { it.copy(deleting = null, error = null) }
        scope.launch {
            request(R.string.notes_error_not_deleted, ::failed) { it.deleteNote(id) } ?: return@launch
            versions.remove(id)
            notes { screen -> screen.copy(notes = screen.notes.filter { it.id != id }, open = screen.open.takeUnless { it == id }) }
        }
    }

    // the content of a photo or a recording of the user, or its thumbnail, fetched once; null when it
    // can not be loaded, which the screen says only for one asked for in full
    suspend fun file(
        id: Int,
        thumbnail: Boolean,
        recording: Boolean = false,
    ): ByteArray? {
        val key = "$id-$thumbnail"
        files[key]?.let { return it }
        val failure = if (recording) R.string.notes_error_recording_not_loaded else R.string.notes_error_photo_not_loaded
        val failed: (UiText) -> Unit = { error -> if (!thumbnail) notes { it.copy(error = error) } }
        return request(failure, failed) { it.file(id, thumbnail, failure) }?.also { files[key] = it }
    }

    fun recordingNotPlayed() = notes { it.copy(error = uiText(R.string.notes_error_recording_not_played)) }

    // the note made now with the photos and recordings, open to be written
    private fun create(
        photos: List<Int>,
        recordings: List<Int>,
    ) {
        if (notesScreen()?.creating != false) return
        notes { it.copy(creating = true, error = null) }
        scope.launch { createNow(photos, recordings) }
    }

    private suspend fun createNow(
        photos: List<Int>,
        recordings: List<Int>,
    ) {
        val labels = listOfNotNull(notesScreen()?.filter)
        val note =
            request(R.string.notes_error_not_created, ::failed) {
                it.createNote(NoteRequest("", NOTE_COLORS.first(), labels, photos, recordings))
            }
        notes { screen ->
            if (note == null) {
                screen.copy(creating = false)
            } else {
                screen.copy(creating = false, notes = listOf(note) + screen.notes.filter { it.id != note.id }, open = note.id)
            }
        }
    }

    // the photo stored in the file storage, null when it could not be read or stored
    private suspend fun uploadPhoto(read: suspend () -> ByteArray?): Int? {
        val jpeg = read()
        if (jpeg == null) {
            photoNotRead()
            return null
        }
        return request(R.string.notes_error_photo_not_stored, ::failed) { it.upload(jpeg, ContentType.Image.JPEG, R.string.notes_error_photo_not_stored) }
    }

    // the text typed is saved now, of the note or of all
    private suspend fun flush(id: Int? = null) {
        val ids = if (id == null) pendingText.keys.toList() else listOfNotNull(id.takeIf { it in pendingText })
        ids.forEach { pending ->
            pendingText.remove(pending)?.cancel()
            save(pending)
        }
    }

    private fun saveNow(id: Int) {
        scope.launch { save(id) }
    }

    // the note as it is here goes to the backend; its answer takes its place unless it changed here
    // meanwhile
    private suspend fun save(id: Int) {
        val note = notesScreen()?.note(id) ?: return
        val version = versions[id] ?: 0
        val request = NoteRequest(note.text, note.color, note.labels.map { it.name }, note.photos.map { it.id }, note.recordings.map { it.id })
        val saved = request(R.string.notes_error_not_saved, ::failed) { it.updateNote(id, request) } ?: return
        if ((versions[id] ?: 0) == version) notes { screen -> screen.copy(notes = screen.notes.map { if (it.id == id) saved else it }) }
    }

    // the note changed here at once; false when it is not here or did not change
    private fun change(
        id: Int,
        change: (NoteResponse) -> NoteResponse,
    ): Boolean {
        val screen = notesScreen() ?: return false
        val note = screen.note(id) ?: return false
        val changed = change(note)
        if (changed == note) return false
        versions[id] = (versions[id] ?: 0) + 1
        state.value = screen.copy(notes = screen.notes.map { if (it.id == id) changed else it })
        return true
    }

    private fun failed(error: UiText) = notes { it.copy(error = error) }

    private fun notesScreen() = state.value as? NotesScreen

    private fun notes(change: (NotesScreen) -> NotesScreen) {
        notesScreen()?.let { state.value = change(it) }
    }

    companion object {
        val WAV = ContentType("audio", "wav")

        // the recorder of a model that does not record, e.g. in a test that does not need one
        private val NO_RECORDER =
            object : Recorder {
                override suspend fun record(): ByteArray? = error("no recorder")

                override fun stop() {}
            }
    }
}
