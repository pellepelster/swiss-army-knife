package io.pelle.sak.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.model.internal.BooleanSetting
import io.pelle.sak.settings.model.internal.ChoiceSetting
import io.pelle.sak.settings.model.internal.SettingChoice
import io.pelle.sak.settings.model.internal.SettingName
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.model.internal.SettingLevel
import io.pelle.sak.settings.model.internal.SettingValue
import io.pelle.sak.settings.model.internal.SettingsGroup
import io.pelle.sak.settings.model.internal.TextSetting
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

// the scope of the settings of the tests
private val TEST = SettingScope("test")

class SettingDefinitionTest {
    private val flag = BooleanSetting(TEST, SettingName("flag"), t("A flag"), t("Turns something on."), default = false)

    private val text = TextSetting(TEST, SettingName("text"), t("A text"), t("Says something."), default = "", maxLength = 5)

    @Test
    fun `a boolean is stored and answered as it is, only true and false are one`() {
        flag.decode(flag.encode(true)) shouldBe true
        flag.decode("yes") shouldBe null
        flag.toJson(true) shouldBe JsonPrimitive(true)

        flag.validate(JsonPrimitive(false)) shouldBe null
        flag.read(JsonPrimitive(false)) shouldBe SettingValue(flag, false)
        listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull, JsonObject(emptyMap())).forEach {
            flag.validate(it) shouldBe ErrorMessage(SettingsTexts.NOT_BOOLEAN, "label" to t("A flag"))
        }
    }

    @Test
    fun `a text is a string of at most its length`() {
        text.decode(text.encode("hello")) shouldBe "hello"
        text.validate(JsonPrimitive("hello")) shouldBe null
        text.validate(JsonPrimitive("hello!")) shouldBe ErrorMessage(SettingsTexts.TOO_LONG, "label" to t("A text"), "max" to 5)
        text.check("hello!") shouldBe ErrorMessage(SettingsTexts.TOO_LONG, "label" to t("A text"), "max" to 5)
        text.validate(JsonPrimitive(true)) shouldBe ErrorMessage(SettingsTexts.NOT_TEXT, "label" to t("A text"))
        text.validate(JsonNull) shouldBe ErrorMessage(SettingsTexts.NOT_TEXT, "label" to t("A text"))
    }

    @Test
    fun `a masked text shows its first characters and a fixed mask`() {
        val masked = TextSetting(TEST, SettingName("masked"), t("Masked"), t("Hides itself."), default = "", mask = 2)
        masked.toApiJson("secret") shouldBe JsonPrimitive("se••••••••")
        masked.toApiJson("se") shouldBe JsonPrimitive("se")
        masked.toApiJson("") shouldBe JsonPrimitive("")
        text.toApiJson("hello") shouldBe JsonPrimitive("hello")
    }

    @Test
    fun `a choice is one of its values`() {
        val choices = listOf(SettingChoice("first", t("First")), SettingChoice("second", t("Second")))
        val choice = ChoiceSetting(TEST, SettingName("choice"), t("A choice"), t("Takes one."), choices, default = "first")

        choice.decode(choice.encode("second")) shouldBe "second"
        // a value stored that is no choice any more
        choice.decode("third") shouldBe null
        choice.toApiJson("second") shouldBe JsonPrimitive("second")
        choice.validate(JsonPrimitive("second")) shouldBe null
        choice.read(JsonPrimitive("second")) shouldBe SettingValue(choice, "second")
        listOf(JsonPrimitive("third"), JsonPrimitive(""), JsonPrimitive("Second"), JsonPrimitive(true), JsonNull).forEach {
            choice.validate(it) shouldBe ErrorMessage(SettingsTexts.NOT_CHOICE, "label" to t("A choice"))
        }
    }

    @Test
    fun `a choice has choices, each value once, and its default is one of them`() {
        val first = SettingChoice("first", t("First"))
        shouldThrow<IllegalArgumentException> { ChoiceSetting(TEST, SettingName("choice"), t("A choice"), t("Takes one."), emptyList(), default = "first") }
        shouldThrow<IllegalArgumentException> { ChoiceSetting(TEST, SettingName("choice"), t("A choice"), t("Takes one."), listOf(first, first), default = "first") }
        shouldThrow<IllegalArgumentException> { ChoiceSetting(TEST, SettingName("choice"), t("A choice"), t("Takes one."), listOf(first), default = "second") }
    }

    @Test
    fun `a setting of a user is never read-only, nor requires a read-only one or one of a user`() {
        shouldThrow<IllegalArgumentException> {
            BooleanSetting(TEST, SettingName("mine"), t("A flag"), t("Turns something on."), default = false, readOnly = true, level = SettingLevel.USER)
        }
        val fixed = BooleanSetting(TEST, SettingName("fixed"), t("Fixed"), t("Given by the code."), default = false, readOnly = true)
        shouldThrow<IllegalArgumentException> { TextSetting(TEST, SettingName("needs"), t("Needs"), t("Needs the flag."), default = "", requires = fixed) }
        val mine = BooleanSetting(TEST, SettingName("mine"), t("A flag"), t("Turns something on."), default = false, level = SettingLevel.USER)
        shouldThrow<IllegalArgumentException> { TextSetting(TEST, SettingName("needs"), t("Needs"), t("Needs the flag."), default = "", requires = mine) }
    }

    @Test
    fun `a group has the settings of its scope, each name once, and is turned on by one of its own settings of the instance`() {
        SettingsGroup(TEST, t("Test"), t("Settings to test with."), listOf(flag, text), enabledBy = flag).setting("text") shouldBe text
        val other = TextSetting(SettingScope("other"), SettingName("other"), t("Other"), t("Says something."), default = "")
        shouldThrow<IllegalArgumentException> { SettingsGroup(TEST, t("Test"), t("Settings to test with."), listOf(flag, other)) }
        val twice = TextSetting(TEST, SettingName("text"), t("A text"), t("Says something."), default = "")
        shouldThrow<IllegalArgumentException> { SettingsGroup(TEST, t("Test"), t("Settings to test with."), listOf(text, twice)) }
        shouldThrow<IllegalArgumentException> { SettingsGroup(TEST, t("Switched"), t("Turned on and off."), listOf(text), enabledBy = flag) }
        val mine = BooleanSetting(TEST, SettingName("mine"), t("A flag"), t("Turns something on."), default = false, level = SettingLevel.USER)
        shouldThrow<IllegalArgumentException> { SettingsGroup(TEST, t("Switched"), t("Turned on and off."), listOf(mine, text), enabledBy = mine) }
    }

    @Test
    fun `a group of the admins has a description, one of the users only needs none`() {
        val mine = BooleanSetting(TEST, SettingName("mine"), t("A flag"), t("Turns something on."), default = false, level = SettingLevel.USER)
        SettingsGroup(TEST, t("Test"), null, listOf(mine)).of(SettingLevel.USER) shouldBe listOf(mine)
        shouldThrow<IllegalArgumentException> { SettingsGroup(TEST, t("Test"), null, listOf(mine, flag)) }
    }
}
