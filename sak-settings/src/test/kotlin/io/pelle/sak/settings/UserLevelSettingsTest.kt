package io.pelle.sak.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.model.SettingUser
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.model.SettingValue
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.TextSetting
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private const val ANNA = "anna@example.org"
private const val BERT = "bert@example.org"

// the settings of each user (SettingUser.USER) next to those of the instance, in one scope and one
// service (SHARED-4)
class UserLevelSettingsTest {
    @TempDir
    lateinit var tempDir: Path

    private val storage by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    // whether the flag of the users counts now, e.g. the AI set up
    private var setUp = true

    private val flag =
        BooleanSetting(
            SettingName("flag"),
            t("A flag"),
            t("Says hello."),
            default = false,
            level = SettingUser.USER,
            unavailable = { if (setUp) null else ErrorMessage(t("Required.")) },
        )
    private val text = TextSetting(SettingName("text"), t("A text"), t("Says something."), default = "hello", maxLength = 5, level = SettingUser.USER)

    // the instance has a setting of its own in the same scope, of the same name as none of the users
    private val greeting = TextSetting(SettingName("greeting"), t("A greeting"), t("Says hello."), default = "hi")

    private val service by lazy { StoredSettingsService(SettingsGroup(SettingScope("app"), t("Base"), t("Required."), listOf(flag, text, greeting)), storage) }

    private fun Validation.values() = (this as Validation.Valid).values

    @Test
    fun `each user has their own values, the default while they set none, apart from the instance`() {
        service.get(flag, ANNA) shouldBe false
        service.set(flag, true, owner = ANNA, user = ANNA) shouldBe null
        service.get(flag, ANNA) shouldBe true
        service.get(flag, BERT) shouldBe false
        // the user is who set it
        storage.get(flag.name, service.scope.name, ANNA) shouldBe "true"
        storage.get(flag.name, service.scope.name) shouldBe null
        // a user sees only the settings of the users, the instance only its own
        service.all(BERT) shouldBe listOf(SettingValue(flag, false), SettingValue(text, "hello"))
        service.all() shouldBe listOf(SettingValue(greeting, "hi"))
        // the code asks for the level it means
        shouldThrow<IllegalArgumentException> { service.get(flag) }
        shouldThrow<IllegalArgumentException> { service.get(greeting, ANNA) }
    }

    @Test
    fun `a request reaches only the settings of its level`() {
        service.validate(mapOf("greeting" to JsonPrimitive("hey")), ANNA) shouldBe
            Validation.Invalid(listOf(ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to "greeting", "scope" to service.scope.name)))
        service.validate(mapOf("flag" to JsonPrimitive(true))) shouldBe
            Validation.Invalid(listOf(ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to "flag", "scope" to service.scope.name)))
        service.validate(mapOf("text" to JsonPrimitive("too long")), ANNA) shouldBe
            Validation.Invalid(listOf(ErrorMessage(SettingsTexts.TOO_LONG, "label" to t("A text"), "max" to 5)))
        service.validate(mapOf("flag" to JsonPrimitive(true), "text" to JsonPrimitive("hey")), ANNA).values() shouldBe
            listOf(SettingValue(flag, true), SettingValue(text, "hey"))
    }

    @Test
    fun `a setting that does not count now keeps its value, can not be turned on, only off, and says why`() {
        service.set(flag, true, owner = ANNA, user = ANNA)
        setUp = false

        service.get(flag, ANNA) shouldBe null
        service.all(ANNA).first() shouldBe SettingValue(flag, true)
        service.unavailable(flag) shouldBe ErrorMessage(t("Required."))
        service.validate(mapOf("flag" to JsonPrimitive(true)), BERT) shouldBe Validation.Unavailable(listOf(ErrorMessage(t("Required."))))
        service.set(flag, true, owner = BERT, user = BERT) shouldBe ErrorMessage(t("Required."))
        // sent back as it is it changes nothing; back to its default it may go
        service.validate(mapOf("flag" to JsonPrimitive(true)), ANNA).values() shouldBe emptyList()
        service.validate(mapOf("flag" to JsonPrimitive(false)), ANNA).values() shouldBe listOf(SettingValue(flag, false))
    }

    @Test
    fun `the registry shows the admins the scopes of the instance, the users the scopes of the users`() {
        val usersOnly = StoredSettingsService(SettingsGroup(SettingScope("users"), t("Other"), null, listOf(text)), storage)
        val registry = SettingsRegistry(listOf(service, usersOnly))
        registry.instanceServices.map { it.scope.name.value } shouldBe listOf("app")
        registry.userServices.map { it.scope.name.value } shouldBe listOf("app", "users")
        registry.values("users") shouldBe null
        registry.update("users", mapOf("text" to JsonPrimitive("x")), owner = "admin@example.org") shouldBe SettingsUpdateResult.NotFound

        registry.updateOfUser(ANNA, "app", mapOf("flag" to JsonPrimitive(true))).shouldBeInstanceOf<SettingsUpdateResult.Updated>()
        service.get(flag, ANNA) shouldBe true
        setUp = false
        registry.updateOfUser(BERT, "app", mapOf("flag" to JsonPrimitive(true))) shouldBe
            SettingsUpdateResult.Unavailable(listOf(ErrorMessage(t("Required."))))
        registry.updateOfUser(BERT, "none", emptyMap()) shouldBe SettingsUpdateResult.NotFound
    }
}
