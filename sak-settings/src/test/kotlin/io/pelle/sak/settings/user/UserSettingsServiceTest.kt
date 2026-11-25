package io.pelle.sak.settings.user

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.settings.BooleanSetting
import io.pelle.sak.settings.SettingName
import io.pelle.sak.settings.SettingScopeName
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.SettingsTexts
import io.pelle.sak.settings.TextSetting
import io.pelle.sak.settings.t
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private const val ANNA = "anna@example.org"
private const val BERT = "bert@example.org"

class UserSettingsServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val settings by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    // whether the flag counts now, e.g. the AI set up
    private var setUp = true

    private val flag = UserSetting(BooleanSetting(SettingName("flag"), t("A flag"), t("Says hello."), default = false), unavailable = { if (setUp) null else t("Required.") })
    private val text = UserSetting(TextSetting(SettingName("text"), t("A text"), t("Says something."), default = "hello", maxLength = 5))
    private val scope = UserSettingScope(SettingScopeName("app"), t("Base"), listOf(flag, text))

    private val service by lazy { UserSettingsService(settings, listOf(scope)) }

    private fun UserSettingsUpdateResult.values() = (this as UserSettingsUpdateResult.Updated).values.associate { it.setting.name.value to it.value }

    @Test
    fun `each user has their own values, the default while they set none, apart from the instance`() {
        service.value(ANNA, scope, flag) shouldBe false
        service.on(ANNA, scope, flag) shouldBe false
        // the instance has a value of the same name and scope, the users do not see it
        settings.set(scope.name, mapOf(flag.name to "true"), owner = "admin@example.org")

        service.update(ANNA, "app", mapOf("flag" to JsonPrimitive(true))).values() shouldBe mapOf("flag" to JsonPrimitive(true), "text" to JsonPrimitive("hello"))
        service.on(ANNA, scope, flag) shouldBe true
        service.on(BERT, scope, flag) shouldBe false
        service.values(BERT, scope).map { it.value } shouldBe listOf(JsonPrimitive(false), JsonPrimitive("hello"))
        // the user is who set it
        settings.get(flag.name, scope.name, ANNA) shouldBe "true"
        settings.get(flag.name, scope.name) shouldBe "true"

        service.update(ANNA, "app", mapOf("flag" to JsonPrimitive(false), "text" to JsonPrimitive("hi"))).values() shouldBe
            mapOf("flag" to JsonPrimitive(false), "text" to JsonPrimitive("hi"))
        service.on(ANNA, scope, flag) shouldBe false
        service.value(BERT, scope, text) shouldBe "hello"
    }

    @Test
    fun `a setting that does not count now keeps its value, can not be turned on and says why`() {
        service.update(ANNA, "app", mapOf("flag" to JsonPrimitive(true)))
        setUp = false

        service.value(ANNA, scope, flag) shouldBe true
        service.on(ANNA, scope, flag) shouldBe false
        service.values(BERT, scope).first().unavailable shouldBe t("Required.")
        service.update(BERT, "app", mapOf("flag" to JsonPrimitive(true))) shouldBe
            UserSettingsUpdateResult.Unavailable(listOf(ErrorMessage(t("Required."))))
        // sent back as it is, it changes nothing; the other settings may still change
        service.update(ANNA, "app", mapOf("flag" to JsonPrimitive(true), "text" to JsonPrimitive("hey"))).values() shouldBe
            mapOf("flag" to JsonPrimitive(true), "text" to JsonPrimitive("hey"))
        // turned off it may be also now
        service.update(ANNA, "app", mapOf("flag" to JsonPrimitive(false))).shouldBeInstanceOf<UserSettingsUpdateResult.Updated>()
        service.value(ANNA, scope, flag) shouldBe false
    }

    @Test
    fun `an unknown scope, an unknown setting or a value not valid sets nothing`() {
        service.update(ANNA, "other", mapOf("flag" to JsonPrimitive(true))) shouldBe UserSettingsUpdateResult.NotFound
        service.update(ANNA, "app", mapOf("flag" to JsonPrimitive(true), "nothing" to JsonPrimitive(1), "text" to JsonPrimitive("too long"))) shouldBe
            UserSettingsUpdateResult.Invalid(
                listOf(
                    ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to "nothing", "scope" to scope.name),
                    ErrorMessage(SettingsTexts.TOO_LONG, "label" to t("A text"), "max" to 5),
                ),
            )
        service.update(ANNA, "app", mapOf("flag" to JsonPrimitive("yes"))).shouldBeInstanceOf<UserSettingsUpdateResult.Invalid>()
        service.value(ANNA, scope, flag) shouldBe false
    }
}
