package io.pelle.sak.settings.user

import io.pelle.sak.api.ErrorMessage

sealed interface UserSettingsUpdateResult {
    // every setting of the scope, as after the update
    data class Updated(
        val values: List<UserSettingValue>,
    ) : UserSettingsUpdateResult

    // nothing was set: an unknown setting or a value not valid, one sentence for each problem
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : UserSettingsUpdateResult

    // nothing was set: a value changed of a setting that does nothing now, why for each
    data class Unavailable(
        val errors: List<ErrorMessage>,
    ) : UserSettingsUpdateResult

    data object NotFound : UserSettingsUpdateResult
}
