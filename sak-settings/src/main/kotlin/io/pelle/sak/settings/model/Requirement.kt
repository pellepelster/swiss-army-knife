package io.pelle.sak.settings.model

// a boolean setting of a scope that must be on, e.g. the AI of the deployment for the AI of the notes
data class Requirement(
    val scope: SettingsGroup,
    val setting: BooleanSetting,
) {
    init {
        require(scope.settings.any { it === setting }) { "the setting ${setting.name} is not one of the scope ${scope.name}" }
        require(!setting.readOnly) { "the setting ${setting.name} is read-only, no setting can require it" }
    }

    override fun toString() = "${scope.name}.${setting.name}"
}
