package io.pelle.sak.backend.labels

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.Slice
import io.pelle.sak.model.labels.Label
import io.pelle.sak.model.labels.LabelRepository
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import io.pelle.sak.model.labels.LabelRename

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

// the labels, shared by every application, the only way to them; the applications that link
// labels hear of every deleted one to drop their links to it
class LabelService(
    private val repository: LabelRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(LabelRepository(database, clock))

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
        val label = repository.insert(normalized) ?: return CreateLabelResult.Exists
        return CreateLabelResult.Created(label)
    }

    fun rename(
        id: Int,
        name: String,
    ): RenameLabelResult {
        val label = repository.byId(id) ?: return RenameLabelResult.NotFound
        val normalized = normalize(name) ?: return RenameLabelResult.InvalidName
        if (normalized == label.name) return RenameLabelResult.Renamed(label)
        // deleted meanwhile, or another label got the name
        return when (val renamed = repository.rename(id, normalized)) {
            is LabelRename.Renamed -> RenameLabelResult.Renamed(renamed.label)
            LabelRename.NotFound -> RenameLabelResult.NotFound
            LabelRename.NameTaken -> RenameLabelResult.Exists
        }
    }

    // the label stays as deleted, the links to it are dropped by the applications
    fun delete(id: Int): DeleteLabelResult {
        if (!repository.delete(id)) return DeleteLabelResult.NotFound
        deleteListeners.forEach { it(id) }
        return DeleteLabelResult.Deleted
    }

    // the labels of the names, normalized (normalize) by the caller, the missing ones are created;
    // sorted by name
    fun resolve(names: Set<String>): List<Label> {
        if (names.isEmpty()) return emptyList()
        val existing = repository.byNames(names).map { it.name }.toSet()
        // one created by someone else meanwhile is there all the same
        (names - existing).forEach { repository.insert(it) }
        return repository.byNames(names)
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
