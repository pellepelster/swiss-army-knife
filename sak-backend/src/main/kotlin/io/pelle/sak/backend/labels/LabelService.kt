package io.pelle.sak.backend.labels

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.Slice
import io.pelle.sak.model.labels.Label
import io.pelle.sak.model.labels.LabelRepository
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

sealed interface CreateLabelResult {
    data class Created(
        val label: Label,
    ) : CreateLabelResult

    data object InvalidName : CreateLabelResult

    data object Exists : CreateLabelResult
}

sealed interface RenameLabelResult {
    data class Renamed(
        val label: Label,
    ) : RenameLabelResult

    data object NotFound : RenameLabelResult

    data object InvalidName : RenameLabelResult

    data object Exists : RenameLabelResult
}

sealed interface DeleteLabelResult {
    data object Deleted : DeleteLabelResult

    data object NotFound : DeleteLabelResult
}

sealed interface ResolveLabelsResult {
    data class Resolved(
        val labels: List<Label>,
    ) : ResolveLabelsResult

    // the names that are no valid label names
    data class InvalidNames(
        val names: List<String>,
    ) : ResolveLabelsResult
}

// the labels, shared by every application, the only way to them; the applications that link
// labels hear of every deleted one to drop their links to it
class LabelService(
    private val repository: LabelRepository,
    private val clock: Clock,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(LabelRepository(database), clock)

    private val deleteListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    fun onDelete(listener: (id: Int) -> Unit) {
        deleteListeners.add(listener)
    }

    // sorted by name
    fun page(
        offset: Long,
        limit: Int,
    ): Slice<Label> = Slice(repository.page(offset, limit), repository.count())

    fun byId(id: Int): Label? = repository.byId(id)

    fun byIds(ids: Collection<Int>): List<Label> = if (ids.isEmpty()) emptyList() else repository.byIds(ids.toSet())

    fun byName(name: String): Label? = normalize(name)?.let { repository.byNames(listOf(it)).singleOrNull() }

    fun create(name: String): CreateLabelResult {
        val normalized = normalize(name) ?: return CreateLabelResult.InvalidName
        val label = repository.insert(normalized, clock.instant()) ?: return CreateLabelResult.Exists
        return CreateLabelResult.Created(label)
    }

    fun rename(
        id: Int,
        name: String,
    ): RenameLabelResult {
        val label = repository.byId(id) ?: return RenameLabelResult.NotFound
        val normalized = normalize(name) ?: return RenameLabelResult.InvalidName
        if (normalized == label.name) return RenameLabelResult.Renamed(label)
        val now = clock.instant()
        if (!repository.rename(id, normalized, now)) return RenameLabelResult.Exists
        return RenameLabelResult.Renamed(label.copy(name = normalized, updatedAt = now))
    }

    // the label stays as deleted, the links to it are dropped by the applications
    fun delete(id: Int): DeleteLabelResult {
        if (!repository.delete(id, clock.instant())) return DeleteLabelResult.NotFound
        deleteListeners.forEach { it(id) }
        return DeleteLabelResult.Deleted
    }

    // the labels of the names, the missing ones are created; sorted by name
    fun resolve(names: Collection<String>): ResolveLabelsResult {
        val invalid = names.filter { normalize(it) == null }
        if (invalid.isNotEmpty()) return ResolveLabelsResult.InvalidNames(invalid)
        val normalized = names.mapNotNull { normalize(it) }.toSet()
        if (normalized.isEmpty()) return ResolveLabelsResult.Resolved(emptyList())

        val existing = repository.byNames(normalized).map { it.name }.toSet()
        // one created by someone else meanwhile is there all the same
        (normalized - existing).forEach { repository.insert(it, clock.instant()) }
        return ResolveLabelsResult.Resolved(repository.byNames(normalized))
    }

    companion object {
        const val MAX_NAME_LENGTH = 64

        private val WHITESPACE = Regex("\\s+")

        // lower case with single spaces, null when blank, too long or with control characters
        fun normalize(name: String): String? {
            val normalized = name.trim().replace(WHITESPACE, " ").lowercase()
            return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH && it.none(Char::isISOControl) }
        }
    }
}
