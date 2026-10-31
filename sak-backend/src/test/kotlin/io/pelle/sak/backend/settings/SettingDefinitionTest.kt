package io.pelle.sak.backend.settings

import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class SettingDefinitionTest {
    private val flag = BooleanSetting(SettingName("flag"), "A flag", "Turns something on.", default = false)

    private val text = TextSetting(SettingName("text"), "A text", "Says something.", default = "", maxLength = 5)

    @Test
    fun `a boolean is stored and answered as it is, only true and false are one`() {
        flag.decode(flag.encode(true)) shouldBe true
        flag.decode("yes") shouldBe null
        flag.toJson(true) shouldBe JsonPrimitive(true)

        flag.validate(JsonPrimitive(false)) shouldBe null
        flag.read(JsonPrimitive(false)) shouldBe SettingValue(flag, false)
        listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull, JsonObject(emptyMap())).forEach {
            flag.validate(it) shouldBe ErrorMessage("\"A flag\" must be true or false.")
        }
    }

    @Test
    fun `a text is a string of at most its length`() {
        text.decode(text.encode("hello")) shouldBe "hello"
        text.validate(JsonPrimitive("hello")) shouldBe null
        text.validate(JsonPrimitive("hello!")) shouldBe ErrorMessage("\"A text\" must be at most 5 characters.")
        text.check("hello!") shouldBe ErrorMessage("\"A text\" must be at most 5 characters.")
        text.validate(JsonPrimitive(true)) shouldBe ErrorMessage("\"A text\" must be a text.")
        text.validate(JsonNull) shouldBe ErrorMessage("\"A text\" must be a text.")
    }

    @Test
    fun `a masked text shows its first characters and a fixed mask`() {
        val masked = TextSetting(SettingName("masked"), "Masked", "Hides itself.", default = "", mask = 2)
        masked.toApiJson("secret") shouldBe JsonPrimitive("se••••••••")
        masked.toApiJson("se") shouldBe JsonPrimitive("se")
        masked.toApiJson("") shouldBe JsonPrimitive("")
        text.toApiJson("hello") shouldBe JsonPrimitive("hello")
    }
}
