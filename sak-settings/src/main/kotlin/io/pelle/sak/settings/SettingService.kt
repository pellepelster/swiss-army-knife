package io.pelle.sak.settings

import io.pelle.sak.model.SYSTEM_OWNER
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.settings.SettingRepository
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingScope
import java.time.Clock

// the settings of the whole deployment that no admin changes
object SystemSettings {
    // where the settings the backend keeps for itself are, e.g. its signing key; served by no api
    val SCOPE = SettingScope("system")
}

// every setting of the controller, the only way to them; the values are handed out as they are, a
// consumer of a setting that is a secret masks it. The values are plain in the table; the whole
// database is to be encrypted
class SettingService(
    private val repository: SettingRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(SettingRepository(database, clock))

    // user null for the setting of the instance, else the one of the user (UserSettingsService)
    fun get(
        name: SettingName,
        scope: SettingScope = SystemSettings.SCOPE,
        user: String? = null,
    ): String? = repository.byNameAndScope(name.value, scope.value, user)?.value

    // false when the name and scope have a setting already
    fun put(
        name: SettingName,
        scope: SettingScope,
        value: String,
        // who sets it (MODEL-5)
        owner: String,
    ): Boolean = repository.insert(name.value, scope.value, value, owner) != null

    // the values of the names in one go, a value replaced stays as a deleted row
    fun set(
        scope: SettingScope,
        values: Map<SettingName, String>,
        // who sets them (MODEL-5)
        owner: String,
        // null for settings of the instance
        user: String? = null,
    ) = repository.replace(scope.value, values.mapKeys { it.key.value }, owner, user)

    // the setting, created once when there is none, by the backend on its own
    fun getOrCreate(
        name: SettingName,
        scope: SettingScope = SystemSettings.SCOPE,
        create: () -> String,
    ): String {
        get(name, scope)?.let { return it }
        put(name, scope, create(), SYSTEM_OWNER)
        // another one may have been put meanwhile, the stored one counts
        return get(name, scope) ?: error("the setting $name of $scope was put but is not there")
    }
}
