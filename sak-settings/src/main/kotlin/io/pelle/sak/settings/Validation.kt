package io.pelle.sak.settings

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.model.SettingValue

// the values of a request checked against the definitions of a group
sealed interface Validation {
    data class Valid(
        val values: List<SettingValue<*>>,
    ) : Validation

    // one sentence for each problem: an unknown setting, a value not valid or not changeable
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : Validation

    // the values are valid, but a setting does not count now, e.g. no AI set up: why for each
    data class Unavailable(
        val errors: List<ErrorMessage>,
    ) : Validation
}
