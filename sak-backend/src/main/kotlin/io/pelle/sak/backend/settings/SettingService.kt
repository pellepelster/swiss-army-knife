package io.pelle.sak.backend.settings

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.settings.SettingRepository
import java.time.Clock

// every setting of the controller, the only way to them; the values are handed out as they are, a
// consumer of a setting that is a secret masks it. The values are plain in the table; the whole
// database is to be encrypted (docs/fdr/FDR-002-sqlite-encryption.md)
class SettingService(
    private val repository: SettingRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(SettingRepository(database, clock))

    fun get(
        name: SettingName,
        scope: SettingScope = SettingScope.System,
    ): String? = repository.byNameAndScope(name.name, scope.value)?.value

    // false when the name and scope have a setting already
    fun put(
        name: SettingName,
        scope: SettingScope,
        value: String,
    ): Boolean = repository.insert(name.name, scope.value, value) != null

    // the setting, created once when there is none
    fun getOrCreate(
        name: SettingName,
        scope: SettingScope = SettingScope.System,
        create: () -> String,
    ): String {
        get(name, scope)?.let { return it }
        put(name, scope, create())
        // another one may have been put meanwhile, the stored one counts
        return get(name, scope) ?: error("the setting $name of $scope was put but is not there")
    }

    // it stays as deleted; false when there was none
    fun delete(
        name: SettingName,
        scope: SettingScope = SettingScope.System,
    ): Boolean = repository.delete(name.name, scope.value)
}
