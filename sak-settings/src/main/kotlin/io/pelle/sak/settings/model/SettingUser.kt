package io.pelle.sak.settings.model

// whose value a setting is (SHARED-4)
enum class SettingUser {
    // one value for the whole instance, set by the admins (/api/v1/settings)
    INSTANCE,

    // a value for each user, set by them (/api/v1/user/settings); the default while they set none
    USER,
}
