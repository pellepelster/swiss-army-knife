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

// the labels of a user, the owner (the e-mail address of the signed in user), shared by every
// application and seen by nobody else, the only way to them; the applications that link labels
// hear of every deleted one to drop their links to it
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
        owner: String,
        offset: Long,
        limit: Int,
    ): Slice<Label> = Slice(repository.page(owner, offset, limit), repository.count(owner))

    fun byId(
        owner: String,
        id: Int,
    ): Label? = repository.byId(owner, id)

    fun byIds(
        owner: String,
        ids: Collection<Int>,
    ): List<Label> = if (ids.isEmpty()) emptyList() else repository.byIds(owner, ids.toSet())

    fun byName(
        owner: String,
        name: String,
    ): Label? = normalize(name)?.let { repository.byNames(owner, listOf(it)).singleOrNull() }

    fun create(
        owner: String,
        name: String,
    ): CreateLabelResult {
        val normalized = normalize(name) ?: return CreateLabelResult.InvalidName
        val label = repository.insert(owner, normalized) ?: return CreateLabelResult.Exists
        return CreateLabelResult.Created(label)
    }

    fun rename(
        owner: String,
        id: Int,
        name: String,
    ): RenameLabelResult {
        val label = repository.byId(owner, id) ?: return RenameLabelResult.NotFound
        val normalized = normalize(name) ?: return RenameLabelResult.InvalidName
        if (normalized == label.name) return RenameLabelResult.Renamed(label)
        // deleted meanwhile, or another label got the name
        return when (val renamed = repository.rename(owner, id, normalized)) {
            is LabelRename.Renamed -> RenameLabelResult.Renamed(renamed.label)
            LabelRename.NotFound -> RenameLabelResult.NotFound
            LabelRename.NameTaken -> RenameLabelResult.Exists
        }
    }

    // the label stays as deleted, the links to it are dropped by the applications
    fun delete(
        owner: String,
        id: Int,
    ): DeleteLabelResult {
        if (!repository.delete(owner, id)) return DeleteLabelResult.NotFound
        deleteListeners.forEach { it(id) }
        return DeleteLabelResult.Deleted
    }

    // the owner's labels of the names, normalized (normalize) by the caller, the missing ones are
    // created; sorted by name
    fun resolve(
        owner: String,
        names: Set<String>,
    ): List<Label> {
        if (names.isEmpty()) return emptyList()
        val existing = repository.byNames(owner, names).map { it.name }.toSet()
        // one created meanwhile, e.g. on another device, is there all the same
        (names - existing).forEach { repository.insert(owner, it) }
        return repository.byNames(owner, names)
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
