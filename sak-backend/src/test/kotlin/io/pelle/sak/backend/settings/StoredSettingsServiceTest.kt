package io.pelle.sak.backend.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private val FLAG = BooleanSetting(SettingName("flag"), "A flag", "Turns something on.", default = false)

private val GREETING = TextSetting(SettingName("greeting"), "A greeting", "Says hello.", default = "hello", maxLength = 10)

private val SCOPE = SettingScope(SettingScopeName("test"), "Test", "Settings to test with.", listOf(FLAG, GREETING))

class StoredSettingsServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val storage by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private val service by lazy { StoredSettingsService(SCOPE, storage) }

    @Test
    fun `answers the defaults until a value is set, typed`() {
        val flag: Boolean? = service.get(FLAG)
        flag shouldBe false
        service.get(GREETING) shouldBe "hello"

        service.set(FLAG, true) shouldBe null
        service.set(GREETING, "hi") shouldBe null

        service.get(FLAG) shouldBe true
        service.get(GREETING) shouldBe "hi"
        service.all() shouldBe listOf(SettingValue(FLAG, true), SettingValue(GREETING, "hi"))
        // stored under its name in the scope
        storage.get(FLAG.name, SCOPE.name) shouldBe "true"
    }

    @Test
    fun `the settings of a scope turned off are null, the switch and the api values are not`() {
        val switch = BooleanSetting(SettingName("enabled"), "On", "Turns the scope on.", default = false)
        val switched =
            StoredSettingsService(
                SettingScope(SettingScopeName("switched"), "Switched", "Turned on and off.", listOf(switch, GREETING), enabledBy = switch),
                storage,
            )

        switched.get(switch) shouldBe false
        switched.get(GREETING) shouldBe null
        switched.all() shouldBe listOf(SettingValue(switch, false), SettingValue(GREETING, "hello"))

        switched.set(switch, true) shouldBe null
        switched.get(GREETING) shouldBe "hello"
    }

    @Test
    fun `a scope is turned on by one of its own settings`() {
        shouldThrow<IllegalArgumentException> {
            SettingScope(SettingScopeName("switched"), "Switched", "Turned on and off.", listOf(GREETING), enabledBy = FLAG)
        }
    }

    @Test
    fun `a read-only setting answers the value given, it is neither stored nor changed`() {
        val fixed = TextSetting(SettingName("fixed"), "Fixed", "Given by the code.", default = "", readOnly = true)
        var value = "first"
        val withFixed =
            StoredSettingsService(
                SettingScope(SettingScopeName("fixed"), "Fixed", "With a fixed value.", listOf(FLAG, fixed)),
                storage,
                ReadOnlyValues().with(fixed) { value },
            )

        withFixed.get(fixed) shouldBe "first"
        value = "second"
        withFixed.all() shouldBe listOf(SettingValue(FLAG, false), SettingValue(fixed, "second"))
        withFixed.set(fixed, "third") shouldBe ErrorMessage("\"Fixed\" can not be changed.")
        (withFixed.validate(mapOf("fixed" to JsonPrimitive("third"))) as Validation.Invalid).errors shouldBe
            listOf(ErrorMessage("\"Fixed\" can not be changed."))
        // sent back as answered it changes nothing
        (withFixed.validate(mapOf("fixed" to JsonPrimitive("second"), "flag" to JsonPrimitive(true))) as Validation.Valid).values shouldBe
            listOf(SettingValue(FLAG, true))
        shouldThrow<IllegalArgumentException> { withFixed.set(listOf(SettingValue(fixed, "third")), owner = "admin@example.org") }
        storage.get(fixed.name, withFixed.scope.name) shouldBe null
    }

    @Test
    fun `a read-only setting without a value is a mistake`() {
        val fixed = TextSetting(SettingName("fixed"), "Fixed", "Given by the code.", default = "", readOnly = true)
        shouldThrow<IllegalArgumentException> {
            StoredSettingsService(SettingScope(SettingScopeName("fixed"), "Fixed", "With a fixed value.", listOf(fixed)), storage)
        }
        shouldThrow<IllegalArgumentException> { ReadOnlyValues().with(FLAG) { true } }
    }

    @Test
    fun `a setting that requires another one is off and can not be changed while that one is off`() {
        val switch = BooleanSetting(SettingName("enabled"), "On", "Turns the base on.", default = false)
        val base = StoredSettingsService(SettingScope(SettingScopeName("base"), "Base", "Required.", listOf(switch)), storage)
        val needs = BooleanSetting(SettingName("needs"), "Needs", "Needs the base.", default = false, requires = Requirement(base.scope, switch))
        val dependent =
            StoredSettingsService(SettingScope(SettingScopeName("dependent"), "Dependent", "Requires.", listOf(needs, GREETING)), storage)
        val error = ErrorMessage("\"Needs\" needs \"On\" of \"Base\" turned on.")

        dependent.get(needs) shouldBe null
        dependent.get(GREETING) shouldBe "hello"
        dependent.set(needs, true) shouldBe error
        (dependent.validate(mapOf("needs" to JsonPrimitive(true))) as Validation.Invalid).errors shouldBe listOf(error)
        // sent back as answered it changes nothing
        (dependent.validate(mapOf("needs" to JsonPrimitive(false))) as Validation.Valid).values shouldBe emptyList()

        base.set(switch, true)
        dependent.set(needs, true) shouldBe null
        dependent.get(needs) shouldBe true

        base.set(switch, false)
        dependent.get(needs) shouldBe null
        dependent.all() shouldBe listOf(SettingValue(needs, true), SettingValue(GREETING, "hello"))
        dependent.effective() shouldBe listOf(SettingValue(needs, false), SettingValue(GREETING, "hello"))

        // a requirement needs its own requirement met
        val chained = BooleanSetting(SettingName("chained"), "Chained", "Needs needs.", default = true, requires = Requirement(dependent.scope, needs))
        val last = StoredSettingsService(SettingScope(SettingScopeName("last"), "Last", "At the end.", listOf(chained)), storage)
        last.get(chained) shouldBe null
        base.set(switch, true)
        last.get(chained) shouldBe true
    }

    @Test
    fun `the typed setter checks the value like the api does`() {
        service.set(GREETING, "a greeting far too long") shouldBe ErrorMessage("\"A greeting\" must be at most 10 characters.")
        service.get(GREETING) shouldBe "hello"
    }

    @Test
    fun `a masked value sent back as the api answered it keeps the stored one`() {
        val secret = TextSetting(SettingName("secret"), "Secret", "Hides itself.", default = "", mask = 2)
        val masked = StoredSettingsService(SettingScope(SettingScopeName("masked"), "Masked", "Hides.", listOf(secret)), storage)
        masked.set(secret, "the secret") shouldBe null

        val valid = masked.validate(mapOf("secret" to JsonPrimitive("th••••••••"))).shouldBeInstanceOf<Validation.Valid>()
        valid.values shouldBe emptyList()
        masked.get(secret) shouldBe "the secret"
    }

    @Test
    fun `a stored value the setting does not read is its default`() {
        storage.set(SCOPE.name, mapOf(FLAG.name to "maybe"), owner = "admin@example.org")
        service.get(FLAG) shouldBe false
    }

    @Test
    fun `validates json values, every problem at once, and sets them in one go`() {
        (
            service.validate(
                mapOf("flag" to JsonPrimitive("true"), "greeting" to JsonPrimitive("a greeting far too long"), "other" to JsonPrimitive(1)),
            ) as Validation.Invalid
        ).errors shouldBe
            listOf(
                ErrorMessage("\"A flag\" must be true or false."),
                ErrorMessage("\"A greeting\" must be at most 10 characters."),
                ErrorMessage("There is no setting \"other\" in \"test\"."),
            )

        val valid = service.validate(mapOf("flag" to JsonPrimitive(true))).shouldBeInstanceOf<Validation.Valid>()
        service.set(valid.values, owner = "admin@example.org")
        service.get(FLAG) shouldBe true
        service.get(GREETING) shouldBe "hello"
    }

    @Test
    fun `a setting of another scope is a mistake`() {
        val other = BooleanSetting(SettingName("flag"), "A flag", "Turns something on.", default = false)
        shouldThrow<IllegalArgumentException> { service.get(other) }
        shouldThrow<IllegalArgumentException> { service.set(other, true) }
        shouldThrow<IllegalArgumentException> { service.set(listOf(SettingValue(GREETING, "a greeting far too long")), owner = "admin@example.org") }
    }
}
