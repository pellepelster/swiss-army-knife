package io.pelle.sak.settings.model

// the level of a setting, whose value it is (SHARED-4); the column level of the table settings is null
// for the instance and the address of the user for a user
enum class SettingLevel {
    // one value for the whole instance, set by the admins (/api/v1/settings)
    INSTANCE,

    // a value for each user, set by them (/api/v1/user/settings); the default while they set none
    USER,
}
