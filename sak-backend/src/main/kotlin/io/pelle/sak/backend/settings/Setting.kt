package io.pelle.sak.backend.settings

// the settings the controller keeps, the scope tells whose
enum class SettingName {
    // the key pair signing the login and session tokens, a secret of the system
    AUTH_SIGNING_KEY,
}

// whose setting it is: the controller's own or the one of a user
sealed interface SettingScope {
    // how it is stored, an e-mail address is never 'system'
    val value: String

    data object System : SettingScope {
        override val value = "system"
    }

    data class User(
        val email: String,
    ) : SettingScope {
        override val value = email.trim().lowercase()
    }
}
