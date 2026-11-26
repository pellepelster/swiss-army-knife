package io.pelle.sak.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.settings.model.internal.BooleanSetting
import io.pelle.sak.settings.model.internal.SettingLevel
import io.pelle.sak.settings.model.internal.SettingName
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.model.internal.SettingsGroup
import io.pelle.sak.settings.model.internal.TextSetting
import org.junit.jupiter.api.Test

private val TEST = SettingScope("test")

private val FLAG = BooleanSetting(TEST, SettingName("flag"), t("A flag"), t("Turns something on."), default = false)

private val GROUP = SettingsGroup(TEST, t("Test"), t("Settings to test with."), listOf(FLAG))

// the groups the api serves: registered once, with their read-only values, required groups first, and
// shown by their level (SET-7)
class SettingsRegistryTest {
    private val registry = SettingsRegistry().also { it.register(SettingsRegistration(GROUP)) }

    @Test
    fun `a group with settings of the users has no check, the check sees the instance only (SET-8)`() {
        val scope = SettingScope("users")
        val text = TextSetting(scope, SettingName("text"), t("A text"), t("Says something."), default = "", level = SettingLevel.USER)
        val group = SettingsGroup(scope, t("Other"), null, listOf(text))
        val error = shouldThrow<IllegalArgumentException> { registry.register(SettingsRegistration(group, check = { emptyList() })) }
        error.message shouldBe "the check of the group users sees the settings of the instance only, the group has settings of the users"
        // without a check it is registered
        registry.register(SettingsRegistration(group))
    }

    @Test
    fun `a group with settings of the users has no switch, the switch turns off the instance only (SET-9)`() {
        val scope = SettingScope("switched")
        val switch = BooleanSetting(scope, SettingName("enabled"), t("On"), t("Turns the scope on."), default = false)
        val text = TextSetting(scope, SettingName("text"), t("A text"), t("Says something."), default = "", level = SettingLevel.USER)
        val error =
            shouldThrow<IllegalArgumentException> {
                registry.register(SettingsRegistration(SettingsGroup(scope, t("Switched"), t("Turned on and off."), listOf(switch, text), enabledBy = switch)))
            }
        error.message shouldBe "the switch of the group switched turns off the settings of the instance only, the group has settings of the users"
        // without the switch it is registered
        registry.register(SettingsRegistration(SettingsGroup(scope, t("Switched"), t("Turned on and off."), listOf(switch, text))))
    }

    @Test
    fun `a group whose requirement is not registered is not registered either`() {
        val baseScope = SettingScope("base")
        val switch = BooleanSetting(baseScope, SettingName("enabled"), t("On"), t("Turns the base on."), default = false)
        val needsScope = SettingScope("needs")
        val needs = BooleanSetting(needsScope, SettingName("needs"), t("Needs"), t("Needs the base."), default = false, requires = switch)
        shouldThrow<IllegalArgumentException> { registry.register(SettingsRegistration(SettingsGroup(needsScope, t("Needs"), t("Requires."), listOf(needs)))) }
        registry.register(SettingsRegistration(SettingsGroup(baseScope, t("Base"), t("Required."), listOf(switch))))
        registry.register(SettingsRegistration(SettingsGroup(needsScope, t("Needs"), t("Requires."), listOf(needs))))
        registry.groups.map { it.name } shouldBe listOf(TEST, baseScope, needsScope)
    }

    @Test
    fun `a group is registered once, with a value for each read-only setting`() {
        shouldThrow<IllegalArgumentException> { registry.register(SettingsRegistration(GROUP)) }
        val scope = SettingScope("fixed")
        val fixed = TextSetting(scope, SettingName("fixed"), t("Fixed"), t("Given by the code."), default = "", readOnly = true)
        shouldThrow<IllegalArgumentException> { registry.register(SettingsRegistration(SettingsGroup(scope, t("Fixed"), t("With a fixed value."), listOf(fixed)))) }
        shouldThrow<IllegalArgumentException> { ReadOnlyValues().with(FLAG) { true } }
    }


    @Test
    fun `the groups are served in the order they are registered, by their level`() {
        val users = SettingScope("users")
        val text = TextSetting(users, SettingName("text"), t("A text"), t("Says something."), default = "", level = SettingLevel.USER)
        registry.register(SettingsRegistration(SettingsGroup(users, t("Other"), null, listOf(text))))

        registry.groups.map { it.name } shouldBe listOf(TEST, users)
        registry.groups(SettingLevel.INSTANCE).map { it.name } shouldBe listOf(TEST)
        registry.groups(SettingLevel.USER).map { it.name } shouldBe listOf(users)
        registry.group("test", SettingLevel.INSTANCE) shouldBe GROUP
        registry.group("test", SettingLevel.USER) shouldBe null
        registry.group("users", SettingLevel.INSTANCE) shouldBe null
    }
}
