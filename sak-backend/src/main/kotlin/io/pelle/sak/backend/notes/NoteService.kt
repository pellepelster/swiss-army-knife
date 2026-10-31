package io.pelle.sak.backend.notes

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.api.Page
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.model.labels.Label
import io.pelle.sak.backend.labels.LabelService
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.model.notes.DEFAULT_LIST
import io.pelle.sak.model.notes.NoteRepository
import io.pelle.sak.model.notes.NoteRow
import java.time.Clock
import java.time.Instant

// the paper colors of the post-its
enum class NoteColor {
    YELLOW,
    PINK,
    BLUE,
    GREEN,
    ORANGE,
    ;

    val id = name.lowercase()

    companion object {
        fun of(id: String): NoteColor? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

// owner is the address of the user the note belongs to; labels are sorted by name, photos (images)
// and recordings (audio) are the ids of files of the file storage in their order
data class Note(
    val id: Int,
    val owner: String,
    val text: String,
    val color: NoteColor,
    val labels: List<Label>,
    val photos: List<Int>,
    val recordings: List<Int>,
    val createdAt: Instant,
    // null until the note is changed
    val updatedAt: Instant?,
)

enum class NoteProblem {
    TEXT_TOO_LONG,
    INVALID_COLOR,
    INVALID_LABEL,
    TOO_MANY_LABELS,
    UNKNOWN_PHOTO,
    TOO_MANY_PHOTOS,
    UNKNOWN_RECORDING,
    TOO_MANY_RECORDINGS,
}

sealed interface SaveNoteResult {
    data class Saved(
        val note: Note,
    ) : SaveNoteResult

    data object NotFound : SaveNoteResult

    // the owner has no list, they get one when they sign in (LIST-1)
    data object NoList : SaveNoteResult

    data class Invalid(
        val problems: Set<NoteProblem>,
    ) : SaveNoteResult
}

sealed interface DeleteNoteResult {
    data object Deleted : DeleteNoteResult

    data object NotFound : DeleteNoteResult
}

// the notes of the notes app, the only way to them; every note belongs to its owner, the address
// of the user that created it, and is only seen and changed by them. The labels are the shared
// ones, a label unknown yet is created when a note gets it; the photos are files uploaded to the
// file storage before
class NoteService(
    private val repository: NoteRepository,
    private val labels: LabelService,
    private val files: FileService,
) : LoginListener {
    constructor(
        database: SakDatabase,
        labels: LabelService,
        files: FileService,
        clock: Clock = Clock.systemUTC(),
    ) : this(NoteRepository(database, clock), labels, files)

    init {
        labels.onDelete(repository::detachLabel)
        // a label deleted without its links being dropped, the label service deletes it first
        repository.detachDeletedLabels()
    }

    // the list of the notes of a user who has none (LIST-1)
    override fun signedIn(owner: String) = repository.ensureList(owner, DEFAULT_LIST)

    // the newest first, of the label or of all when label is null; an unknown label has no notes
    fun page(
        owner: String,
        offset: Long,
        limit: Int,
        label: String? = null,
    ): Page<Note> {
        val labelId =
            label?.let { name ->
                labels.byName(owner, name)?.id ?: return Page.empty()
            }
        return Page(notes(owner, repository.page(owner, offset, limit, labelId)), repository.count(owner, labelId))
    }

    fun byId(
        owner: String,
        id: Int,
    ): Note? = repository.byId(owner, id)?.let { notes(owner, listOf(it)).single() }

    fun create(
        owner: String,
        text: String,
        color: String,
        labelNames: Collection<String> = emptyList(),
        photos: List<Int> = emptyList(),
        recordings: List<Int> = emptyList(),
    ): SaveNoteResult {
        // before the check, which creates the labels not known yet
        val listId = repository.ownList(owner) ?: return SaveNoteResult.NoList
        return when (val checked = check(owner, text, color, labelNames, photos, recordings)) {
            is Checked.Invalid -> SaveNoteResult.Invalid(checked.problems)
            is Checked.Valid -> {
                val row = repository.insert(owner, listId, text, checked.color.id, checked.labels.ids(), checked.files)
                SaveNoteResult.Saved(row.toNote(checked.labels, checked.photos, checked.recordings))
            }
        }
    }

    // replaces the text, the color, the labels, the photos and the recordings
    fun update(
        owner: String,
        id: Int,
        text: String,
        color: String,
        labelNames: Collection<String> = emptyList(),
        photos: List<Int> = emptyList(),
        recordings: List<Int> = emptyList(),
    ): SaveNoteResult {
        // before the check, which creates the labels not known yet: a note that is not there, or
        // another owner's, creates none
        repository.byId(owner, id) ?: return SaveNoteResult.NotFound
        return when (val checked = check(owner, text, color, labelNames, photos, recordings)) {
            is Checked.Invalid -> SaveNoteResult.Invalid(checked.problems)
            is Checked.Valid -> {
                val updated =
                    repository.update(owner, id, text, checked.color.id, checked.labels.ids(), checked.files)
                        ?: return SaveNoteResult.NotFound
                SaveNoteResult.Saved(updated.toNote(checked.labels, checked.photos, checked.recordings))
            }
        }
    }

    // the note stays as deleted
    fun delete(
        owner: String,
        id: Int,
    ): DeleteNoteResult = if (repository.delete(owner, id)) DeleteNoteResult.Deleted else DeleteNoteResult.NotFound

    private sealed interface Checked {
        data class Valid(
            val color: NoteColor,
            val labels: List<Label>,
            val photos: List<Int>,
            val recordings: List<Int>,
        ) : Checked {
            // as they are stored, the photos first
            val files get() = photos + recordings
        }

        data class Invalid(
            val problems: Set<NoteProblem>,
        ) : Checked
    }

    // the labels are only created when the note is valid otherwise; a photo or a recording twice is
    // kept once, a photo must be an image and a recording audio the owner uploaded to the file storage
    private fun check(
        owner: String,
        text: String,
        color: String,
        labelNames: Collection<String>,
        photos: List<Int>,
        recordings: List<Int>,
    ): Checked {
        val problems = mutableSetOf<NoteProblem>()
        if (text.length > MAX_TEXT_LENGTH) problems += NoteProblem.TEXT_TOO_LONG
        val noteColor = NoteColor.of(color)
        if (noteColor == null) problems += NoteProblem.INVALID_COLOR
        val normalized = labelNames.map { LabelService.normalize(it) }
        if (null in normalized) problems += NoteProblem.INVALID_LABEL
        // the same label in another case or with other blanks is one
        val names = normalized.filterNotNull().toSet()
        if (names.size > MAX_LABELS) problems += NoteProblem.TOO_MANY_LABELS
        val distinctPhotos = photos.distinct()
        if (distinctPhotos.size > MAX_PHOTOS) problems += NoteProblem.TOO_MANY_PHOTOS
        val distinctRecordings = recordings.distinct()
        if (distinctRecordings.size > MAX_RECORDINGS) problems += NoteProblem.TOO_MANY_RECORDINGS
        val stored = files.byIds(owner, distinctPhotos + distinctRecordings)
        if (distinctPhotos.any { stored[it]?.contentType?.startsWith("image/") != true }) problems += NoteProblem.UNKNOWN_PHOTO
        if (distinctRecordings.any { stored[it]?.contentType?.startsWith("audio/") != true }) problems += NoteProblem.UNKNOWN_RECORDING
        if (problems.isNotEmpty()) return Checked.Invalid(problems)

        return Checked.Valid(noteColor!!, labels.resolve(owner, names), distinctPhotos, distinctRecordings)
    }

    // the labels and the files of all the notes are read at once, the files are told apart by their type
    private fun notes(
        owner: String,
        rows: List<NoteRow>,
    ): List<Note> {
        val byId = labels.byIds(owner, rows.flatMap { it.labelIds }).associateBy { it.id }
        val stored = files.byIds(owner, rows.flatMap { it.fileIds })
        fun of(
            row: NoteRow,
            type: String,
        ) = row.fileIds.filter { stored[it]?.contentType?.startsWith(type) == true }
        return rows.map { row -> row.toNote(row.labelIds.mapNotNull { byId[it] }.sortedBy { it.name }, of(row, "image/"), of(row, "audio/")) }
    }

    private fun List<Label>.ids() = map { it.id }.toSet()

    private fun NoteRow.toNote(
        labels: List<Label>,
        photos: List<Int>,
        recordings: List<Int>,
    ) = Note(id, owner, text, NoteColor.of(color) ?: error("note $id has the unknown color '$color'"), labels, photos, recordings, createdAt, updatedAt)

    companion object {
        const val MAX_TEXT_LENGTH = 10_000

        const val MAX_LABELS = 10

        const val MAX_PHOTOS = 20

        const val MAX_RECORDINGS = 20
    }
}
