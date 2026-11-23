package io.pelle.sak.backend.notes

import io.pelle.sak.backend.labels.INVALID_LABEL_NAME
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.DeleteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.core.Language
import io.pelle.sak.core.render
import io.pelle.sak.api.Page
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.model.labels.Label
import io.pelle.sak.backend.labels.LabelService
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.model.notes.NoteWriteResult
import io.pelle.sak.model.notes.NoteRepository
import io.pelle.sak.model.notes.NoteRow
import java.time.Clock
import java.time.Instant

// the paper colors of the post-its
@Serializable
enum class NoteColor {
    @SerialName("yellow")
    YELLOW,

    @SerialName("pink")
    PINK,

    @SerialName("blue")
    BLUE,

    @SerialName("green")
    GREEN,

    @SerialName("orange")
    ORANGE,
    ;

    val id = name.lowercase()

    companion object {
        fun of(id: String): NoteColor? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

// what can be wrong with a note, each with the message the api answers (API-1), in the order they are
// answered
enum class NoteProblem(
    val message: ErrorMessage,
) {
    TEXT_TOO_LONG(ErrorMessage(NotesTexts.TEXT_TOO_LONG, "max" to NoteService.MAX_TEXT_LENGTH)),
    INVALID_COLOR(ErrorMessage(NotesTexts.INVALID_COLOR, "colors" to NoteColor.entries.joinToString(", ") { it.id })),
    INVALID_LABEL(INVALID_LABEL_NAME),
    TOO_MANY_LABELS(ErrorMessage(NotesTexts.TOO_MANY_LABELS, "max" to NoteService.MAX_LABELS)),
    UNKNOWN_PHOTO(ErrorMessage(NotesTexts.UNKNOWN_PHOTO)),
    TOO_MANY_PHOTOS(ErrorMessage(NotesTexts.TOO_MANY_PHOTOS, "max" to NoteService.MAX_PHOTOS)),
    UNKNOWN_RECORDING(ErrorMessage(NotesTexts.UNKNOWN_RECORDING)),
    TOO_MANY_RECORDINGS(ErrorMessage(NotesTexts.TOO_MANY_RECORDINGS, "max" to NoteService.MAX_RECORDINGS)),
    ;

    // a photo or a recording the note names that the owner does not have (API-6)
    val unknownFile get() = this == UNKNOWN_PHOTO || this == UNKNOWN_RECORDING
}

sealed interface NoteSaveResult {
    data class Saved(
        val note: Note,
    ) : NoteSaveResult

    data object NotFound : NoteSaveResult

    // the owner has no list, they get one when they sign in (NOTES-1)
    data object NoList : NoteSaveResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : NoteSaveResult

    // only photos or recordings that are not there for the owner (API-6)
    data class UnknownFiles(
        val errors: List<ErrorMessage>,
    ) : NoteSaveResult

    companion object {
        // the result of problems: the unknown files alone are an entity the request refers to, any
        // other problem makes the note invalid
        fun of(problems: Set<NoteProblem>): NoteSaveResult {
            val errors = problems.sorted().map { it.message }
            return if (problems.all { it.unknownFile }) UnknownFiles(errors) else Invalid(errors)
        }
    }
}

// the notes, the only way to them; only the owner, who created a note, sees and changes it. The
// labels are the shared ones, an unknown label is created when a note gets it; photos and
// recordings are files uploaded before
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
        labels.linkedBy(repository)
    }

    // the first list of a user who never had one, in the language of the sign-in, the one of all their
    // notes (LIST-1, NOTES-1)
    override fun signedIn(
        owner: String,
        language: Language,
    ) = repository.ensureList(owner, NotesTexts.FIRST_LIST.render(language))

    // the labels a note of the owner has, the ones to filter by
    fun usedLabels(owner: String): List<Label> = repository.usedLabels(owner)

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
    ): NoteSaveResult {
        // before the check, which creates the labels not known yet
        val listId = repository.ownList(owner) ?: return NoteSaveResult.NoList
        return when (val checked = check(owner, text, color, labelNames, photos, recordings)) {
            is CheckedNote.Invalid -> NoteSaveResult.of(checked.problems)
            is CheckedNote.Valid -> saved(repository.insert(owner, listId, text, checked.color.id, checked.labels.ids(), checked.files), checked)
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
    ): NoteSaveResult {
        // before the check, which creates the labels not known yet: a note that is not there, or
        // another owner's, creates none
        repository.byId(owner, id) ?: return NoteSaveResult.NotFound
        return when (val checked = check(owner, text, color, labelNames, photos, recordings)) {
            is CheckedNote.Invalid -> NoteSaveResult.of(checked.problems)
            is CheckedNote.Valid -> saved(repository.update(owner, id, text, checked.color.id, checked.labels.ids(), checked.files), checked)
        }
    }

    // the note stays as deleted
    fun delete(
        owner: String,
        id: Int,
    ): DeleteResult = DeleteResult.of(repository.delete(owner, id) != null)

    // what the repository made of the checked values: it checks again in its transaction that the list
    // and the files are the owner's (MODEL-6), and leaves out a label deleted since the check
    private fun saved(
        save: NoteWriteResult,
        checked: CheckedNote.Valid,
    ): NoteSaveResult =
        when (save) {
            is NoteWriteResult.Saved ->
                NoteSaveResult.Saved(save.note.toNote(checked.labels.filter { it.id in save.note.labelIds }, checked.photos, checked.recordings))
            NoteWriteResult.NotFound -> NoteSaveResult.NotFound
            NoteWriteResult.NoList -> NoteSaveResult.NoList
            is NoteWriteResult.UnknownFiles ->
                NoteSaveResult.of(
                    setOfNotNull(
                        NoteProblem.UNKNOWN_PHOTO.takeIf { checked.photos.any { it in save.fileIds } },
                        NoteProblem.UNKNOWN_RECORDING.takeIf { checked.recordings.any { it in save.fileIds } },
                    ),
                )
        }

    private sealed interface CheckedNote {
        data class Valid(
            val color: NoteColor,
            val labels: List<Label>,
            val photos: List<Int>,
            val recordings: List<Int>,
        ) : CheckedNote {
            // as they are stored, the photos first
            val files get() = photos + recordings
        }

        data class Invalid(
            val problems: Set<NoteProblem>,
        ) : CheckedNote
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
    ): CheckedNote {
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
        if (problems.isNotEmpty()) return CheckedNote.Invalid(problems)

        return CheckedNote.Valid(noteColor!!, labels.resolve(owner, names), distinctPhotos, distinctRecordings)
    }

    // the labels and files of all the notes in one read, the files told apart by their type
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
