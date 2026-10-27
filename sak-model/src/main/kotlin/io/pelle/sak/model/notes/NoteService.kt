package io.pelle.sak.model.notes

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.Slice
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

// labels are sorted by name
data class Note(
    val id: Int,
    val text: String,
    val color: NoteColor,
    val labels: List<Label>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class NoteProblem {
    TEXT_TOO_LONG,
    INVALID_COLOR,
    INVALID_LABEL,
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

// the notes of the notes app, the only way to them; the labels are the shared ones, a label
// unknown yet is created when a note gets it
class NoteService internal constructor(
    private val repository: NoteRepository,
    private val labels: LabelService,
    private val clock: Clock,
) {
    constructor(
        database: SakDatabase,
        labels: LabelService,
        clock: Clock = Clock.systemUTC(),
    ) : this(NoteRepository(database), labels, clock)

    init {
        labels.onDelete(repository::detachLabel)
    }

    // the newest first, of the label or of all when label is null; an unknown label has no notes
    fun page(
        offset: Long,
        limit: Int,
        label: String? = null,
    ): Slice<Note> {
        val labelId =
            label?.let { name ->
                labels.byName(name)?.id ?: return Slice(emptyList(), 0)
            }
        return Slice(notes(repository.page(offset, limit, labelId)), repository.count(labelId))
    }

    fun byId(id: Int): Note? = repository.byId(id)?.let { notes(listOf(it)).single() }

    fun create(
        text: String,
        color: String,
        labelNames: Collection<String> = emptyList(),
    ): SaveNoteResult =
        when (val checked = check(text, color, labelNames)) {
            is Checked.Invalid -> SaveNoteResult.Invalid(checked.problems)
            is Checked.Valid -> {
                val row = repository.insert(text, checked.color.id, checked.labels.ids(), clock.instant())
                SaveNoteResult.Saved(row.toNote(checked.labels))
            }
        }

    // replaces the text, the color and the labels
    fun update(
        id: Int,
        text: String,
        color: String,
        labelNames: Collection<String> = emptyList(),
    ): SaveNoteResult {
        val note = repository.byId(id) ?: return SaveNoteResult.NotFound
        return when (val checked = check(text, color, labelNames)) {
            is Checked.Invalid -> SaveNoteResult.Invalid(checked.problems)
            is Checked.Valid -> {
                val now = clock.instant()
                if (!repository.update(id, text, checked.color.id, checked.labels.ids(), now)) return SaveNoteResult.NotFound
                SaveNoteResult.Saved(note.copy(text = text, color = checked.color.id, updatedAt = now).toNote(checked.labels))
            }
        }
    }

    // the note stays as deleted
    fun delete(id: Int): DeleteNoteResult =
        if (repository.delete(id, clock.instant())) DeleteNoteResult.Deleted else DeleteNoteResult.NotFound

    private sealed interface Checked {
        data class Valid(
            val color: NoteColor,
            val labels: List<Label>,
        ) : Checked

        data class Invalid(
            val problems: Set<NoteProblem>,
        ) : Checked
    }

    // the labels are only created when the note is valid otherwise
    private fun check(
        text: String,
        color: String,
        labelNames: Collection<String>,
    ): Checked {
        val problems = mutableSetOf<NoteProblem>()
        if (text.length > MAX_TEXT_LENGTH) problems += NoteProblem.TEXT_TOO_LONG
        val noteColor = NoteColor.of(color)
        if (noteColor == null) problems += NoteProblem.INVALID_COLOR
        if (labelNames.any { LabelService.normalize(it) == null }) problems += NoteProblem.INVALID_LABEL
        if (problems.isNotEmpty()) return Checked.Invalid(problems)

        return when (val resolved = labels.resolve(labelNames)) {
            is ResolveLabelsResult.InvalidNames -> Checked.Invalid(setOf(NoteProblem.INVALID_LABEL))
            is ResolveLabelsResult.Resolved -> Checked.Valid(noteColor!!, resolved.labels)
        }
    }

    // the labels of all the notes are read at once
    private fun notes(rows: List<NoteRow>): List<Note> {
        val byId = labels.byIds(rows.flatMap { it.labelIds }).associateBy { it.id }
        return rows.map { row -> row.toNote(row.labelIds.mapNotNull { byId[it] }.sortedBy { it.name }) }
    }

    private fun List<Label>.ids() = map { it.id }.toSet()

    private fun NoteRow.toNote(labels: List<Label>) =
        Note(id, text, NoteColor.of(color) ?: error("note $id has the unknown color '$color'"), labels, createdAt, updatedAt)

    companion object {
        const val MAX_TEXT_LENGTH = 10_000
    }
}
