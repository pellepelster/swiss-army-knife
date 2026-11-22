package io.pelle.sak.backend.labels

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.api.Page
import io.pelle.sak.model.labels.Label
import io.pelle.sak.model.labels.LabelRepository
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import io.pelle.sak.model.labels.LabelNameUpdateResult

sealed interface LabelCreateResult {
    data class Created(
        val label: Label,
    ) : LabelCreateResult

    data object InvalidName : LabelCreateResult

    data object Exists : LabelCreateResult
}

sealed interface LabelRenameResult {
    data class Renamed(
        val label: Label,
    ) : LabelRenameResult

    data object NotFound : LabelRenameResult

    data object InvalidName : LabelRenameResult

    data object Exists : LabelRenameResult
}

sealed interface LabelDeleteResult {
    data object Deleted : LabelDeleteResult

    data object NotFound : LabelDeleteResult
}

// the only way to the labels of a user (the owner); every app shares them, no other user sees them.
// The apps that link labels hear of every deleted one to drop their links to it
class LabelService(
    private val repository: LabelRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(LabelRepository(database, clock))

    private val deleteListeners = CopyOnWriteArrayList<(owner: String, id: Int) -> Unit>()

    fun onDelete(listener: (owner: String, id: Int) -> Unit) {
        deleteListeners.add(listener)
    }

    // sorted by name
    fun page(
        owner: String,
        offset: Long,
        limit: Int,
    ): Page<Label> = Page(repository.page(owner, offset, limit), repository.count(owner))

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
    ): LabelCreateResult {
        val normalized = normalize(name) ?: return LabelCreateResult.InvalidName
        val label = repository.insert(owner, normalized) ?: return LabelCreateResult.Exists
        return LabelCreateResult.Created(label)
    }

    fun rename(
        owner: String,
        id: Int,
        name: String,
    ): LabelRenameResult {
        val label = repository.byId(owner, id) ?: return LabelRenameResult.NotFound
        val normalized = normalize(name) ?: return LabelRenameResult.InvalidName
        if (normalized == label.name) return LabelRenameResult.Renamed(label)
        // deleted meanwhile, or another label got the name
        return when (val renamed = repository.rename(owner, id, normalized)) {
            is LabelNameUpdateResult.Renamed -> LabelRenameResult.Renamed(renamed.label)
            LabelNameUpdateResult.NotFound -> LabelRenameResult.NotFound
            LabelNameUpdateResult.NameTaken -> LabelRenameResult.Exists
        }
    }

    // the label stays as deleted, the links to it are dropped by the applications
    fun delete(
        owner: String,
        id: Int,
    ): LabelDeleteResult {
        if (!repository.delete(owner, id)) return LabelDeleteResult.NotFound
        deleteListeners.forEach { it(owner, id) }
        return LabelDeleteResult.Deleted
    }

    // the names normalized by the caller; the missing labels are created; sorted by name
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
