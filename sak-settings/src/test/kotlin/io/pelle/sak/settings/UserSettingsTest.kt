package io.pelle.sak.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.settings.SettingRepository
import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.model.SettingLevel
import io.pelle.sak.settings.model.SettingValue
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.TextSetting
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val ANNA = "anna@example.org"
private const val BERT = "bert@example.org"

private val APP = SettingScope("app")

// the settings of each user (SettingLevel.USER) next to those of the instance, in one group (SHARED-4)
class UserSettingsTest {
    @TempDir
    lateinit var tempDir: Path

    private val repository by lazy { SettingRepository(SakDatabase.open(tempDir.resolve("sak.db"))) }

    // whether the flag of the users counts now, e.g. the AI set up
    private var setUp = true

    private val flag =
        BooleanSetting(
            APP,
            SettingName("flag"),
            t("A flag"),
            t("Says hello."),
            default = false,
            level = SettingLevel.USER,
            unavailable = { if (setUp) null else ErrorMessage(t("Required.")) },
        )
    private val text = TextSetting(APP, SettingName("text"), t("A text"), t("Says something."), default = "hello", maxLength = 5, level = SettingLevel.USER)

    // the instance has a setting of its own in the same group
    private val greeting = TextSetting(APP, SettingName("greeting"), t("A greeting"), t("Says hello."), default = "hi")

    private val group = SettingsGroup(APP, t("Base"), t("Required."), listOf(flag, text, greeting))

    private val settings by lazy { SettingService(repository).also { it.register(group) } }

    private fun Validation.values() = (this as Validation.Valid).values

    @Test
    fun `each user has their own values, the default while they set none, apart from the instance`() {
        settings.get(flag, ANNA) shouldBe false
        settings.set(flag, true, owner = ANNA, user = ANNA) shouldBe null
        settings.on(flag, ANNA) shouldBe true
        settings.on(flag, BERT) shouldBe false
        // the user is who set it
        repository.byNameAndScope("flag", "app", ANNA)!!.let {
            it.value shouldBe "true"
            it.owner shouldBe ANNA
        }
        repository.byNameAndScope("flag", "app") shouldBe null
        // a user sees only the settings of the users, the instance only its own
        settings.all(group, BERT) shouldBe listOf(SettingValue(flag, false), SettingValue(text, "hello"))
        settings.all(group) shouldBe listOf(SettingValue(greeting, "hi"))
        // the code asks for the level it means
        shouldThrow<IllegalArgumentException> { settings.get(flag) }
        shouldThrow<IllegalArgumentException> { settings.get(greeting, ANNA) }
    }

    @Test
    fun `a request reaches only the settings of its level`() {
        settings.validate(group, mapOf("greeting" to JsonPrimitive("hey")), ANNA) shouldBe
            Validation.Invalid(listOf(ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to "greeting", "scope" to APP)))
        settings.validate(group, mapOf("flag" to JsonPrimitive(true))) shouldBe
            Validation.Invalid(listOf(ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to "flag", "scope" to APP)))
        settings.validate(group, mapOf("text" to JsonPrimitive("too long")), ANNA) shouldBe
            Validation.Invalid(listOf(ErrorMessage(SettingsTexts.TOO_LONG, "label" to t("A text"), "max" to 5)))
        settings.validate(group, mapOf("flag" to JsonPrimitive(true), "text" to JsonPrimitive("hey")), ANNA).values() shouldBe
            listOf(SettingValue(flag, true), SettingValue(text, "hey"))
    }

    @Test
    fun `a setting that does not count now keeps its value, can not be turned on, only off, and says why`() {
        settings.set(flag, true, owner = ANNA, user = ANNA)
        setUp = false

        settings.get(flag, ANNA) shouldBe null
        settings.stored(flag, ANNA) shouldBe true
        settings.unavailable(flag) shouldBe ErrorMessage(t("Required."))
        settings.validate(group, mapOf("flag" to JsonPrimitive(true)), BERT) shouldBe Validation.Unavailable(listOf(ErrorMessage(t("Required."))))
        settings.set(flag, true, owner = BERT, user = BERT) shouldBe ErrorMessage(t("Required."))
        // sent back as it is it changes nothing; back to its default it may go
        settings.validate(group, mapOf("flag" to JsonPrimitive(true)), ANNA).values() shouldBe emptyList()
        settings.validate(group, mapOf("flag" to JsonPrimitive(false)), ANNA).values() shouldBe listOf(SettingValue(flag, false))
    }

    @Test
    fun `a user updates their own settings of a group, the answer is theirs`() {
        settings.update(group, mapOf("flag" to JsonPrimitive(true)), owner = ANNA, user = ANNA)
            .shouldBeInstanceOf<SettingsUpdateResult.Updated>().values shouldBe listOf(SettingValue(flag, true), SettingValue(text, "hello"))
        settings.on(flag, ANNA) shouldBe true
        setUp = false
        settings.update(group, mapOf("flag" to JsonPrimitive(true)), owner = BERT, user = BERT) shouldBe
            SettingsUpdateResult.Unavailable(listOf(ErrorMessage(t("Required."))))
    }

    @Test
    fun `a setting of a user is neither read-only nor required`() {
        shouldThrow<IllegalArgumentException> {
            TextSetting(APP, SettingName("fixed"), t("Fixed"), t("Given by the code."), default = "", readOnly = true, level = SettingLevel.USER)
        }
        shouldThrow<IllegalArgumentException> {
            BooleanSetting(APP, SettingName("needs"), t("Needs"), t("Needs the flag."), default = false, requires = flag)
        }
    }
}
