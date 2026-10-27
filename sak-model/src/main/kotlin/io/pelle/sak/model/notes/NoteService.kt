package io.pelle.sak.model.notes

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.Slice
import io.pelle.sak.model.files.FileService
import io.pelle.sak.model.labels.Label
import io.pelle.sak.model.labels.LabelService
import io.pelle.sak.model.labels.ResolveLabelsResult
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
    val updatedAt: Instant,
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
class NoteService internal constructor(
    private val repository: NoteRepository,
    private val labels: LabelService,
    private val files: FileService,
    private val clock: Clock,
) {
    constructor(
        database: SakDatabase,
        labels: LabelService,
        files: FileService,
        clock: Clock = Clock.systemUTC(),
    ) : this(NoteRepository(database), labels, files, clock)

    init {
        labels.onDelete(repository::detachLabel)
    }

    // the newest first, of the label or of all when label is null; an unknown label has no notes
    fun page(
        owner: String,
        offset: Long,
        limit: Int,
        label: String? = null,
    ): Slice<Note> {
        val labelId =
            label?.let { name ->
                labels.byName(name)?.id ?: return Slice(emptyList(), 0)
            }
        return Slice(notes(repository.page(owner, offset, limit, labelId)), repository.count(owner, labelId))
    }

    fun byId(
        owner: String,
        id: Int,
    ): Note? = repository.byId(owner, id)?.let { notes(listOf(it)).single() }

    fun create(
        owner: String,
        text: String,
        color: String,
        labelNames: Collection<String> = emptyList(),
        photos: List<Int> = emptyList(),
        recordings: List<Int> = emptyList(),
    ): SaveNoteResult =
        when (val checked = check(text, color, labelNames, photos, recordings)) {
            is Checked.Invalid -> SaveNoteResult.Invalid(checked.problems)
            is Checked.Valid -> {
                val row = repository.insert(owner, text, checked.color.id, checked.labels.ids(), checked.files, clock.instant())
                SaveNoteResult.Saved(row.toNote(checked.labels, checked.photos, checked.recordings))
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
        val note = repository.byId(owner, id) ?: return SaveNoteResult.NotFound
        return when (val checked = check(text, color, labelNames, photos, recordings)) {
            is Checked.Invalid -> SaveNoteResult.Invalid(checked.problems)
            is Checked.Valid -> {
                val now = clock.instant()
                if (!repository.update(owner, id, text, checked.color.id, checked.labels.ids(), checked.files, now)) {
                    return SaveNoteResult.NotFound
                }
                val updated = note.copy(text = text, color = checked.color.id, files = checked.files, updatedAt = now)
                SaveNoteResult.Saved(updated.toNote(checked.labels, checked.photos, checked.recordings))
            }
        }
    }

    // the note stays as deleted
    fun delete(
        owner: String,
        id: Int,
    ): DeleteNoteResult = if (repository.delete(owner, id, clock.instant())) DeleteNoteResult.Deleted else DeleteNoteResult.NotFound

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
    // kept once, a photo must be an image and a recording audio of the file storage
    private fun check(
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
        if (labelNames.any { LabelService.normalize(it) == null }) problems += NoteProblem.INVALID_LABEL
        // the same label in another case or with other blanks is one
        if (labelNames.mapNotNull { LabelService.normalize(it) }.distinct().size > MAX_LABELS) problems += NoteProblem.TOO_MANY_LABELS
        val distinctPhotos = photos.distinct()
        if (distinctPhotos.size > MAX_PHOTOS) problems += NoteProblem.TOO_MANY_PHOTOS
        val distinctRecordings = recordings.distinct()
        if (distinctRecordings.size > MAX_RECORDINGS) problems += NoteProblem.TOO_MANY_RECORDINGS
        val stored = files.byIds(distinctPhotos + distinctRecordings)
        if (distinctPhotos.any { stored[it]?.contentType?.startsWith("image/") != true }) problems += NoteProblem.UNKNOWN_PHOTO
        if (distinctRecordings.any { stored[it]?.contentType?.startsWith("audio/") != true }) problems += NoteProblem.UNKNOWN_RECORDING
        if (problems.isNotEmpty()) return Checked.Invalid(problems)

        return when (val resolved = labels.resolve(labelNames)) {
            is ResolveLabelsResult.InvalidNames -> Checked.Invalid(setOf(NoteProblem.INVALID_LABEL))
            is ResolveLabelsResult.Resolved -> Checked.Valid(noteColor!!, resolved.labels, distinctPhotos, distinctRecordings)
        }
    }

    // the labels and the files of all the notes are read at once, the files are told apart by their type
    private fun notes(rows: List<NoteRow>): List<Note> {
        val byId = labels.byIds(rows.flatMap { it.labelIds }).associateBy { it.id }
        val stored = files.byIds(rows.flatMap { it.files })
        fun of(
            row: NoteRow,
            type: String,
        ) = row.files.filter { stored[it]?.contentType?.startsWith(type) == true }
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
