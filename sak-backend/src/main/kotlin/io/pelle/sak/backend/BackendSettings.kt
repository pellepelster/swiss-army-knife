package io.pelle.sak.backend

import io.pelle.sak.settings.SystemSettings
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.TextSetting

// the settings the backend keeps for itself in the scope of the system (SystemSettings.SCOPE), in no
// group, so no api serves them
object BackendSettings {
    // the key pair signing the login and session tokens, a secret of the system, made once
    val AUTH_SIGNING_KEY =
        TextSetting(
            SystemSettings.SCOPE,
            SettingName("AUTH_SIGNING_KEY"),
            SystemTexts.SIGNING_KEY_LABEL,
            SystemTexts.SIGNING_KEY_DESCRIPTION,
            default = "",
            maxLength = 16384,
            mask = 0,
        )
}
