package io.pelle.sak.backend

import io.pelle.sak.settings.model.SettingName

// the settings the backend keeps for itself in the scope of the system (SystemSettings.SCOPE), served
// by no api
object BackendSettings {
    // the key pair signing the login and session tokens, a secret of the system
    val AUTH_SIGNING_KEY = SettingName("AUTH_SIGNING_KEY")
}
