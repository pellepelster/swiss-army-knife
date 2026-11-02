package io.pelle.sak.notes

import io.pelle.sak.api.model.FileResponse
import io.pelle.sak.api.model.LabelResponse
import io.pelle.sak.api.model.NoteRequest
import io.pelle.sak.api.model.NoteResponse
import io.pelle.sak.api.model.PhotoResponse
import io.pelle.sak.api.model.RecordingResponse
import io.ktor.http.ContentType
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.AppUpdate
import io.pelle.sak.core.update.Updater
import io.pelle.sak.notes.api.NotesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

// the colors of a note, the first is the one of a new note
val NOTE_COLORS: List<NoteResponse.Color> = NoteResponse.Color.entries

// the limits of a note, the backend takes no more (NoteService, LabelService of sak-backend, AN-24)
const val MAX_LABELS = 10
const val MAX_LABEL_LENGTH = 64
const val MAX_TEXT_LENGTH = 10_000
const val MAX_PHOTOS = 20
const val MAX_RECORDINGS = 20

// how long typing pauses before the text is saved, as the web app
const val SAVE_TEXT_AFTER_MS = 600L

// how long a photo or a recording taken off a note can come back (UX-4.2); the backend learns of it after
const val UNDO_REMOVE_MS = 2_000L

// the notes load again when the app comes to the front only when the last load is older (AN-51)
const val RELOAD_AFTER_MS = 30_000L

// the bytes of the photos and recordings fetched that are kept, the ones used last (AN-21)
private const val MAX_FILE_BYTES = 32L * 1024 * 1024

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

// a photo or a recording taken off a note, which undo puts back at its place until the time is up
// (UX-4.2); number is its place counted from 1, as the screen names it
data class Removal(
    val noteId: Int,
    val number: Int,
    val photo: PhotoResponse? = null,
    val recording: RecordingResponse? = null,
)

// the notes of the signed in user once signed in: the wall of their notes, newest first, of the label
// what a load answers: the notes of the filter, the labels to suggest, the labels the notes have (null
// when they could not be read) and whether the AI is offered
private data class Loaded(
    val notes: List<NoteResponse>,
    val shared: List<String>,
    val used: List<String>?,
    val ai: Boolean,
)

// filtered by or all (NOTES-1: the lists are not shown, the app opens on the notes, LIST-3)
data class NotesScreen(
    override val user: String,
    override val update: AppUpdate? = null,
    override val updateNotice: UiText? = null,
    val notes: List<NoteResponse> = emptyList(),
    // the labels to choose from when one is added to a note: the ones of all apps and of the notes, sorted
    val labels: List<String> = emptyList(),
    // the labels a note has, sorted: the ones the wall filters by (GET /notes/labels)
    val used: List<String> = emptyList(),
    // the label the wall shows the notes of, null for all
    val filter: String? = null,
    // how many notes there are, of the last load of all; the count of All while a label is filtered
    val allCount: Int? = null,
    val loading: Boolean = true,
    val error: UiText? = null,
    // what the user is told that is no error, e.g. that a recording stopped at its longest
    val notice: UiText? = null,
    // whether the notes offer the AI (GET /notes/settings), off until the backend says otherwise
    val ai: Boolean = false,
    // the note open in its own screen, to write and change it
    val open: Int? = null,
    // the note whose delete asks first (UX-4.1)
    val deleting: Int? = null,
    // the notes being deleted, a second delete waits for nothing
    val deletingNow: Set<Int> = emptySet(),
    val recording: RecordingFor? = null,
    // when the recording started, what its time is counted from, also after a rotation
    val recordingSince: Instant? = null,
    // a new note being created, e.g. from a photo
    val creating: Boolean = false,
    // the notes a photo or a recording is being stored for
    val uploading: Set<Int> = emptySet(),
    // the photos and recordings whose text is being discovered
    val discovering: Set<Int> = emptySet(),
    // the photo or recording just taken off, which undo puts back
    val removal: Removal? = null,
) : AppScreen {
    override fun withUser(user: String) = copy(user = user)

    override fun withUpdate(
        update: AppUpdate?,
        updateNotice: UiText?,
    ) = copy(update = update, updateNotice = updateNotice)

    fun note(id: Int?) = notes.firstOrNull { it.id == id }

    // the chips of the filter: the labels a note has, and the one filtered by while it is on, also when no
    // note has it any more, so it can be turned off
    val chips: List<String> get() = (used + listOfNotNull(filter)).distinct().sorted()
}

// the logic of the notes app, without android: the notes are kept by the backend, a change shows at
// once and is saved in the background, the text once typing pauses; one save of a note at a time, a
// change while it runs sends the note once more after its answer (AN-4). A load keeps what changed here
// since it started (AN-5). Photos are scaled down and recordings made by the ui and the recorder,
// uploaded to the file storage and attached. The AI discovers the text of a photo or a recording only
// when an admin turned it on (the privacy rule: its consent names OpenRouter); the backend refuses it
// otherwise
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

    // the notes as the backend answered them last, what a change it refused goes back to (AN-24)
    private val stored = mutableMapOf<Int, NoteResponse>()

    // the save running of each note, and the notes changed while it runs (AN-4)
    private val saving = mutableMapOf<Int, Job>()
    private val dirty = mutableSetOf<Int>()

    // the notes changed, created or saving since the load running started: they stay as they are here
    // (AN-5); the notes deleted here, which no load brings back
    private val changedWhileLoading = mutableSetOf<Int>()
    private val deleted = mutableSetOf<Int>()

    private var loading: Job? = null
    private var loadedAt: Instant? = null

    // the photos handed over before the notes were there, e.g. after android ended the app behind the
    // camera (AN-27): the note they are for (null for a new note) and how to read them
    private val waitingPhotos = mutableListOf<Pair<Int?, suspend () -> ByteArray?>>()

    // the time of the undo of a removal
    private var removing: Job? = null

    // the photos and recordings fetched, by their file and whether it is the thumbnail
    private val files = FileCache(MAX_FILE_BYTES)

    override fun showApp(stored: Stored) {
        state.value = NotesScreen(stored.email.orEmpty())
        load()
    }

    // what is typed or taken off is sent before the session goes
    override suspend fun signingOut() {
        commitRemoval()
        flush()
        saving.values.toList().joinAll()
    }

    override fun sessionEnding() {
        pendingText.values.forEach { it.cancel() }
        pendingText.clear()
        removing?.cancel()
        recorder.stop()
    }

    override fun clearSession() {
        files.clear()
        versions.clear()
        stored.clear()
        dirty.clear()
        deleted.clear()
        changedWhileLoading.clear()
        loadedAt = null
        // the photos of the session that went are not attached; read, so the ui deletes the file of the camera
        waitingPhotos.toList().forEach { (_, read) -> discard(read) }
        waitingPhotos.clear()
    }

    // the notes of the filter, every page, the labels and whether the AI is offered; the notes changed
    // here since the load started stay as they are here (AN-5)
    fun load() {
        notes { it.copy(loading = true, error = null) }
        loading?.cancel()
        changedWhileLoading.clear()
        changedWhileLoading += pendingText.keys + saving.filterValues { it.isActive }.keys
        loading =
            scope.launch {
                val filter = notesScreen()?.filter
                val loaded =
                    request(R.string.notes_errors_not_loaded, ::failed) { api ->
                        val all = mutableListOf<NoteResponse>()
                        while (true) {
                            val page = api.notes(filter, all.size, SakApi.PAGE_LIMIT)
                            all += page.items
                            if (page.items.size < SakApi.PAGE_LIMIT || all.size >= page.pagination.total) break
                        }
                        Loaded(
                            all,
                            runCatching { api.labels().map { it.name } }.getOrDefault(emptyList()),
                            runCatching { api.usedLabels().map { it.name } }.getOrNull(),
                            aiOf(api),
                        )
                    }
                if (loaded == null) {
                    notes { it.copy(loading = false) }
                    return@launch
                }
                val (all, shared, used, ai) = loaded
                all.filter { it.id !in changedWhileLoading }.forEach { stored[it.id] = it }
                notes { screen ->
                    val here = screen.notes.filter { it.id in changedWhileLoading }.associateBy { it.id }
                    val fresh = all.filter { it.id !in deleted }
                    // a note created here that the answer does not have yet comes first
                    val created = here.values.filter { note -> fresh.none { it.id == note.id } }
                    val notes = created + fresh.map { here[it.id] ?: it }
                    screen.copy(
                        notes = notes,
                        labels = (shared + notes.flatMap { note -> note.labels.map { it.name } }).distinct().sorted(),
                        used = used ?: screen.used,
                        allCount = if (filter == null) notes.size else screen.allCount,
                        ai = ai,
                        loading = false,
                    )
                }
                loadedAt = clock.instant()
                attachWaitingPhotos()
            }
    }

    // the app came to the front again: the notes may have changed elsewhere (no stream of changes), loaded
    // again when the last load is older than RELOAD_AFTER_MS
    fun resume() {
        if (notesScreen() == null) return
        val last = loadedAt
        if (last == null || Duration.between(last, clock.instant()).toMillis() >= RELOAD_AFTER_MS) load()
    }

    // the app goes to the background: a recording stops (Android records only silence for an app in the
    // background, AN-22), what is typed or taken off is saved now, also when the app closes (AN-29)
    fun pause() {
        recorder.stop()
        launchLasting {
            commitRemoval()
            flush()
        }
    }

    fun refresh() = load()

    // the wall shows the notes of the label, or all with null
    fun filter(label: String?) {
        val screen = notesScreen() ?: return
        if (screen.filter == label) return
        notes { it.copy(filter = label, notes = emptyList(), open = null) }
        load()
    }

    fun dismissError() = notes { it.copy(error = null) }

    fun dismissNotice() = notes { it.copy(notice = null) }

    // a note in its own screen, or back to the wall, its text saved first
    fun open(id: Int?) {
        val previous = notesScreen()?.open
        notes { it.copy(open = id, error = null) }
        if (previous != null && previous != id) launchLasting { flush(previous) }
    }

    // a new empty note, labelled with the label the wall shows, open to be written
    fun create() = create(photos = emptyList(), recordings = emptyList())

    // a new note with the photo; read is the photo, scaled down already, null when it could not be read.
    // Before the notes are there, the photo waits for them (AN-27); read is always called once
    fun createWithPhoto(read: suspend () -> ByteArray?) {
        val screen = notesScreen()
        if (screen == null || screen.loading) {
            waitingPhotos += null to read
            return
        }
        if (screen.creating) {
            discard(read)
            return
        }
        notes { it.copy(creating = true, error = null) }
        scope.launch {
            val file = uploadPhoto(read)
            if (file == null) {
                notes { it.copy(creating = false) }
                return@launch
            }
            createNow(photos = listOf(file.id), recordings = emptyList())
        }
    }

    // the text, at most MAX_TEXT_LENGTH characters (AN-24), saved once typing pauses
    fun setText(
        id: Int,
        text: String,
    ) {
        val limited = text.take(MAX_TEXT_LENGTH)
        if (!change(id) { if (it.text == limited) it else it.copy(text = limited) }) return
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
        color: NoteResponse.Color,
    ) {
        if (change(id) { if (it.color == color) it else it.copy(color = color) }) save(id)
    }

    // a blank label, one the note has already or one more than MAX_LABELS changes nothing; a label longer
    // than MAX_LABEL_LENGTH is refused with its reason (AN-24)
    fun addLabel(
        id: Int,
        label: String,
    ) {
        val name = normalizeLabel(label)
        if (name.isEmpty()) return
        if (name.length > MAX_LABEL_LENGTH) {
            notes { it.copy(error = uiText(R.string.notes_label_too_long, MAX_LABEL_LENGTH)) }
            return
        }
        val changed =
            change(id) { note ->
                if (note.labels.any { it.name == name } || note.labels.size >= MAX_LABELS) {
                    note
                } else {
                    // a stand-in until the save answers the note with the label as it is stored; the
                    // backend stores the time
                    note.copy(labels = (note.labels + LabelResponse(id = 0, name = name, createdAt = "")).sortedBy { it.name })
                }
            }
        if (changed) {
            notes { it.copy(labels = (it.labels + name).distinct().sorted()) }
            save(id)
        }
    }

    fun removeLabel(
        id: Int,
        label: String,
    ) {
        if (change(id) { note -> if (note.labels.none { it.name == label }) note else note.copy(labels = note.labels.filter { it.name != label }) }) save(id)
    }

    // the photo is attached once it is stored; read is the photo, scaled down already, null when it
    // could not be read. Before the notes are there, the photo waits for them (AN-27); a note with
    // MAX_PHOTOS takes no more (AN-24); read is always called once
    fun addPhoto(
        id: Int,
        read: suspend () -> ByteArray?,
    ) {
        val screen = notesScreen()
        if (screen == null || screen.loading) {
            waitingPhotos += id to read
            return
        }
        val note = screen.note(id)
        if (note == null || note.photos.size >= MAX_PHOTOS) {
            if (note != null) notes { it.copy(error = uiText(R.string.notes_photos_full, MAX_PHOTOS)) }
            discard(read)
            return
        }
        notes { it.copy(uploading = it.uploading + id, error = null) }
        scope.launch {
            val file = uploadPhoto(read)
            notes { it.copy(uploading = it.uploading - id) }
            if (file != null &&
                change(id) {
                    if (it.photos.any { photo -> photo.id == file.id } || it.photos.size >= MAX_PHOTOS) it else it.copy(photos = it.photos + PhotoResponse(file.id, file.url, file.thumbnailUrl))
                }
            ) {
                save(id)
            }
        }
    }

    // the photo goes from the note at once, undo puts it back until UNDO_REMOVE_MS; the file stays in
    // the storage, other notes may show it too (NOTES-2)
    fun removePhoto(
        id: Int,
        fileId: Int,
    ) {
        val index = notesScreen()?.note(id)?.photos?.indexOfFirst { it.id == fileId } ?: return
        if (index < 0) return
        commitRemoval()
        val photo = notesScreen()?.note(id)?.photos?.get(index) ?: return
        if (!change(id) { note -> note.copy(photos = note.photos.filter { it.id != fileId }) }) return
        removed(Removal(id, index + 1, photo = photo))
    }

    fun removeRecording(
        id: Int,
        fileId: Int,
    ) {
        val index = notesScreen()?.note(id)?.recordings?.indexOfFirst { it.id == fileId } ?: return
        if (index < 0) return
        commitRemoval()
        val recording = notesScreen()?.note(id)?.recordings?.get(index) ?: return
        if (!change(id) { note -> note.copy(recordings = note.recordings.filter { it.id != fileId }) }) return
        removed(Removal(id, index + 1, recording = recording))
    }

    // the photo or recording taken off last back at its place; nothing is sent, the backend did not
    // learn of it
    fun undoRemoval() {
        val removal = notesScreen()?.removal ?: return
        removing?.cancel()
        removing = null
        notes { it.copy(removal = null) }
        change(removal.noteId) { note ->
            when {
                removal.photo != null && note.photos.none { it.id == removal.photo.id } ->
                    note.copy(photos = note.photos.toMutableList().apply { add((removal.number - 1).coerceIn(0, size), removal.photo) })
                removal.recording != null && note.recordings.none { it.id == removal.recording.id } ->
                    note.copy(recordings = note.recordings.toMutableList().apply { add((removal.number - 1).coerceIn(0, size), removal.recording) })
                else -> note
            }
        }
        // a save of another change sent it without; the note as it is here goes again
        if (removal.noteId in dirty || saving[removal.noteId]?.isActive == true || stored[removal.noteId] != notesScreen()?.note(removal.noteId)) {
            save(removal.noteId)
        }
    }

    // starts recording for a note or a new note, a second call stops it; the recording is stored and
    // attached, a new note is made with it. A note with MAX_RECORDINGS takes no more (AN-24)
    fun record(target: RecordingFor) {
        val screen = notesScreen() ?: return
        if (screen.recording != null) {
            recorder.stop()
            return
        }
        if (target is RecordingFor.Note && (screen.note(target.id)?.recordings?.size ?: 0) >= MAX_RECORDINGS) {
            notes { it.copy(error = uiText(R.string.notes_recordings_full, MAX_RECORDINGS)) }
            return
        }
        notes { it.copy(recording = target, recordingSince = clock.instant(), error = null, notice = null) }
        // also when the app closes during the upload of the recording (AN-29)
        launchLasting {
            val wav =
                try {
                    recorder.record()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    notes { it.copy(error = uiText(R.string.notes_errors_microphone)) }
                    null
                } finally {
                    notes { it.copy(recording = null, recordingSince = null) }
                }
            // stopped before it recorded, there is nothing to keep
            if (wav == null) return@launchLasting
            if (wav.size >= WAV_HEADER + RECORDING_RATE * 2 * MAX_RECORDING_SECONDS) {
                notes { it.copy(notice = uiText(R.string.notes_recording_longest, MAX_RECORDING_SECONDS / 60)) }
            }
            val id = (target as? RecordingFor.Note)?.id
            if (id != null) notes { it.copy(uploading = it.uploading + id) } else notes { it.copy(creating = true) }
            val file =
                request(R.string.notes_errors_recording_not_stored, ::failed) { api ->
                    api.upload(wav, WAV, R.string.notes_errors_recording_not_stored)
                }
            if (id == null) {
                if (file == null) notes { it.copy(creating = false) } else createNow(photos = emptyList(), recordings = listOf(file.id))
                return@launchLasting
            }
            notes { it.copy(uploading = it.uploading - id) }
            if (file != null &&
                change(id) {
                    if (it.recordings.any { recording -> recording.id == file.id } || it.recordings.size >= MAX_RECORDINGS) {
                        it
                    } else {
                        it.copy(recordings = it.recordings + RecordingResponse(file.id, file.url, file.thumbnailUrl))
                    }
                }
            ) {
                save(id)
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
            val text = request(R.string.notes_errors_not_discovered, ::failed) { it.discoverText(fileId) }
            notes { it.copy(discovering = it.discovering - fileId) }
            when {
                text == null -> {}
                text.isBlank() ->
                    notes { it.copy(error = uiText(if (recording) R.string.notes_errors_no_text_in_recording else R.string.notes_error_no_text_in_photo)) }
                change(id) { note -> note.copy(text = (if (note.text.isBlank()) text else "${note.text.trimEnd()}\n\n$text").take(MAX_TEXT_LENGTH)) } -> {
                    pendingText.remove(id)?.cancel()
                    save(id)
                }
            }
        }
    }

    fun askDelete(id: Int) = notes { it.copy(deleting = id) }

    fun dismissDelete() = notes { it.copy(deleting = null) }

    // the note goes for good, its files stay in the storage (NOTES-2); back to the wall. A second delete
    // while it runs does nothing
    fun delete(id: Int) {
        val screen = notesScreen() ?: return
        if (id in screen.deletingNow) return
        pendingText.remove(id)?.cancel()
        notes { it.copy(deleting = null, error = null, deletingNow = it.deletingNow + id) }
        launchLasting {
            val done = request(R.string.notes_errors_not_deleted, ::failed) { it.deleteNote(id) }
            notes { it.copy(deletingNow = it.deletingNow - id) }
            if (done == null) return@launchLasting
            deleted += id
            versions.remove(id)
            stored.remove(id)
            if (notesScreen()?.removal?.noteId == id) {
                removing?.cancel()
                removing = null
            }
            notes { current ->
                current.copy(
                    notes = current.notes.filter { it.id != id },
                    open = current.open.takeUnless { it == id },
                    allCount = current.allCount?.minus(1),
                    removal = current.removal?.takeUnless { it.noteId == id },
                )
            }
            reloadUsed()
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
        return request(failure, failed) { it.file(id, thumbnail, failure) }?.also { files.put(key, it) }
    }

    fun recordingNotPlayed() = notes { it.copy(error = uiText(R.string.notes_error_recording_not_played)) }

    // whether the AI is offered: off when the backend can not say
    private suspend fun aiOf(api: NotesApi): Boolean =
        try {
            api.discoverAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

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
                it.createNote(NoteRequest("", NOTE_COLORS.first().value, labels, photos, recordings))
            }
        if (note != null) {
            stored[note.id] = note
            // a load running does not have it yet (AN-5)
            changedWhileLoading += note.id
            if (note.labels.isNotEmpty()) reloadUsed()
        }
        notes { screen ->
            if (note == null) {
                screen.copy(creating = false)
            } else {
                screen.copy(
                    creating = false,
                    notes = listOf(note) + screen.notes.filter { it.id != note.id },
                    open = note.id,
                    allCount = screen.allCount?.plus(1),
                )
            }
        }
    }

    // the photos that waited for the notes, attached or made into notes now
    private fun attachWaitingPhotos() {
        val waiting = waitingPhotos.toList()
        waitingPhotos.clear()
        waiting.forEach { (id, read) -> if (id == null) createWithPhoto(read) else addPhoto(id, read) }
    }

    // a photo that is not attached is still read, so the ui deletes the file of the camera
    private fun discard(read: suspend () -> ByteArray?) {
        scope.launch { runCatching { read() } }
    }

    // the photo stored in the file storage, null when it could not be read or stored
    private suspend fun uploadPhoto(read: suspend () -> ByteArray?): FileResponse? {
        val jpeg = read()
        if (jpeg == null) {
            photoNotRead()
            return null
        }
        return request(R.string.notes_errors_photo_not_stored, ::failed) { it.upload(jpeg, ContentType.Image.JPEG, R.string.notes_errors_photo_not_stored) }
    }

    // the removal shown with its undo; the backend learns of it once the time is up
    private fun removed(removal: Removal) {
        notes { it.copy(removal = removal) }
        removing =
            scope.launch {
                delay(UNDO_REMOVE_MS)
                commitRemoval()
            }
    }

    // the removal shown goes to the backend now
    private fun commitRemoval() {
        val removal = notesScreen()?.removal ?: return
        removing?.cancel()
        removing = null
        notes { it.copy(removal = null) }
        save(removal.noteId)
    }

    // the text typed is saved now, of the note or of all; returns once it is saved
    private suspend fun flush(id: Int? = null) {
        val ids = if (id == null) pendingText.keys.toList() else listOfNotNull(id.takeIf { it in pendingText })
        ids
            .map { pending ->
                pendingText.remove(pending)?.cancel()
                save(pending)
            }.joinAll()
    }

    // the note as it is here goes to the backend, one save of a note at a time: a save asked for while
    // one runs marks the note, which goes once more after the answer (AN-4). The save is done also when
    // the app closes (AN-29); its job ends once the note is sent
    private fun save(id: Int): Job {
        saving[id]?.let { running ->
            if (running.isActive) {
                dirty += id
                return running
            }
        }
        val job =
            launchLasting {
                do {
                    dirty -= id
                    if (!send(id)) {
                        dirty -= id
                        break
                    }
                } while (id in dirty)
            }
        saving[id] = job
        return job
    }

    // the note sent once; its answer takes its place unless it changed here meanwhile. A change the
    // backend refuses (400, e.g. over a limit) goes back to the note as it was stored (AN-24); false
    // when it was not saved
    private suspend fun send(id: Int): Boolean {
        val note = notesScreen()?.note(id) ?: return false
        if (id in deleted) return false
        val version = versions[id] ?: 0
        var refused = false
        val saved =
            request(R.string.notes_errors_not_saved, { error -> if (id !in deleted && id !in (notesScreen()?.deletingNow ?: emptySet())) failed(error) }) { api ->
                try {
                    api.updateNote(id, note.request())
                } catch (e: ApiException) {
                    refused = e.status == 400 || e.status == 422
                    throw e
                }
            }
        if (saved == null) {
            if (refused) rollBack(id)
            return false
        }
        stored[id] = saved
        if ((versions[id] ?: 0) == version) notes { screen -> screen.copy(notes = screen.notes.map { if (it.id == id) saved else it }) }
        reloadUsed()
        return true
    }

    // the labels the notes have, again after a note changed: a label goes once its last note lost it; on
    // a failure the ones before stay, the wall still works
    private fun reloadUsed() {
        scope.launch {
            val used = request(R.string.notes_errors_not_loaded, {}) { api -> api.usedLabels().map { it.name } } ?: return@launch
            notes { it.copy(used = used) }
        }
    }

    // the note back as the backend stored it last, the change it refused undone; the error says why
    private fun rollBack(id: Int) {
        val server = stored[id] ?: return
        pendingText.remove(id)?.cancel()
        versions[id] = (versions[id] ?: 0) + 1
        notes { screen -> screen.copy(notes = screen.notes.map { if (it.id == id) server else it }) }
    }

    // the note changed here at once, the error of an action before goes; false when it is not here or did
    // not change
    private fun change(
        id: Int,
        change: (NoteResponse) -> NoteResponse,
    ): Boolean {
        val screen = notesScreen() ?: return false
        val note = screen.note(id) ?: return false
        val changed = change(note)
        if (changed == note) return false
        versions[id] = (versions[id] ?: 0) + 1
        changedWhileLoading += id
        state.value = screen.copy(notes = screen.notes.map { if (it.id == id) changed else it }, error = null)
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

// what the backend takes of a note
private fun NoteResponse.request() = NoteRequest(text, color.value, labels.map { it.name }, photos.map { it.id }, recordings.map { it.id })

// the files fetched, the ones used last, at most maxBytes of them; one larger than that is kept alone
private class FileCache(
    private val maxBytes: Long,
) {
    private val files = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    operator fun get(key: String): ByteArray? = files[key]

    @Synchronized
    fun put(
        key: String,
        content: ByteArray,
    ) {
        files.put(key, content)?.let { bytes -= it.size }
        bytes += content.size
        val eldest = files.entries.iterator()
        while (bytes > maxBytes && files.size > 1 && eldest.hasNext()) {
            val entry = eldest.next()
            if (entry.key == key) continue
            bytes -= entry.value.size
            eldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        files.clear()
        bytes = 0
    }
}
