package io.pelle.sak.settings

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.model.SettingValue

sealed interface SettingsUpdateResult {
    // every setting of the level of the group, as after the update
    data class Updated(
        val values: List<SettingValue<*>>,
    ) : SettingsUpdateResult

    // nothing was set, one sentence for each problem
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : SettingsUpdateResult

    // nothing was set: a setting does not count now, why for each (409)
    data class Unavailable(
        val errors: List<ErrorMessage>,
    ) : SettingsUpdateResult

    data object NotFound : SettingsUpdateResult
}
