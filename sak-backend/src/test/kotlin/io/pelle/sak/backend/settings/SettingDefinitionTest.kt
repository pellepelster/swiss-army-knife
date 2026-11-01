package io.pelle.sak.backend.settings

import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class SettingDefinitionTest {
    private val flag = BooleanSetting(SettingName("flag"), t("A flag"), t("Turns something on."), default = false)

    private val text = TextSetting(SettingName("text"), t("A text"), t("Says something."), default = "", maxLength = 5)

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
        val masked = TextSetting(SettingName("masked"), t("Masked"), t("Hides itself."), default = "", mask = 2)
        masked.toApiJson("secret") shouldBe JsonPrimitive("se••••••••")
        masked.toApiJson("se") shouldBe JsonPrimitive("se")
        masked.toApiJson("") shouldBe JsonPrimitive("")
        text.toApiJson("hello") shouldBe JsonPrimitive("hello")
    }
}
