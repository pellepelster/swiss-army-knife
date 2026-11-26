package io.pelle.sak.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.settings.SettingRepository
import io.pelle.sak.settings.model.internal.BooleanSetting
import io.pelle.sak.settings.model.internal.ChoiceSetting
import io.pelle.sak.settings.model.internal.SettingChoice
import io.pelle.sak.settings.model.internal.SettingName
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.model.internal.SettingLevel
import io.pelle.sak.settings.model.internal.SettingValue
import io.pelle.sak.settings.model.internal.SettingsGroup
import io.pelle.sak.settings.model.internal.TextSetting
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val ADMIN = "admin@example.org"

private val TEST = SettingScope("test")

private val FLAG = BooleanSetting(TEST, SettingName("flag"), t("A flag"), t("Turns something on."), default = false)

private val GREETING = TextSetting(TEST, SettingName("greeting"), t("A greeting"), t("Says hello."), default = "hello", maxLength = 10)

private val GROUP = SettingsGroup(TEST, t("Test"), t("Settings to test with."), listOf(FLAG, GREETING))

// the settings of the instance (SettingLevel.INSTANCE), asked with their definitions; the groups the
// api serves registered with their checks and read-only values
class SettingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val repository by lazy { SettingRepository(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private val settings by lazy { SettingService(repository).also { it.register(SettingsRegistration(GROUP)) } }

    private fun stored(
        scope: SettingScope,
        name: SettingName,
    ) = repository.byNameAndScope(name.value, scope.value)?.value

    @Test
    fun `answers the defaults until a value is set, typed`() {
        val flag: Boolean? = settings.get(FLAG)
        flag shouldBe false
        settings.get(GREETING) shouldBe "hello"

        settings.set(FLAG, true) shouldBe null
        settings.set(GREETING, "hi") shouldBe null

        settings.on(FLAG) shouldBe true
        settings.get(GREETING) shouldBe "hi"
        settings.all(GROUP) shouldBe listOf(SettingValue(FLAG, true), SettingValue(GREETING, "hi"))
        // stored under its name in its scope
        stored(TEST, FLAG.name) shouldBe "true"
    }

    @Test
    fun `a setting of no registered group is read and set all the same`() {
        val loose = TextSetting(SettingScope("loose"), SettingName("value"), t("A text"), t("Says something."), default = "")
        settings.get(loose) shouldBe ""
        settings.set(loose, "set") shouldBe null
        settings.get(loose) shouldBe "set"
    }

    @Test
    fun `a setting that requires one of a group not registered says so, read on its own or in its group (SET-1)`() {
        val baseScope = SettingScope("base")
        val switch = BooleanSetting(baseScope, SettingName("enabled"), t("On"), t("Turns the base on."), default = false)
        val needs = BooleanSetting(SettingScope("loose"), SettingName("needs"), t("Needs"), t("Needs the base."), default = false, requires = switch)

        val error = shouldThrow<IllegalStateException> { settings.get(needs) }
        error.message shouldBe "the setting loose.needs requires base.enabled, whose group is not registered"
        shouldThrow<IllegalStateException> { settings.set(needs, true) }
        // registered, the requirement counts as for every other setting
        settings.register(SettingsRegistration(SettingsGroup(baseScope, t("Base"), t("Required."), listOf(switch))))
        settings.get(needs) shouldBe null
        settings.set(switch, true)
        settings.get(needs) shouldBe false
    }

    @Test
    fun `the settings of a group turned off are null, the switch and the stored values are not`() {
        val scope = SettingScope("switched")
        val switch = BooleanSetting(scope, SettingName("enabled"), t("On"), t("Turns the scope on."), default = false)
        val text = TextSetting(scope, SettingName("greeting"), t("A greeting"), t("Says hello."), default = "hello", maxLength = 10)
        val switched = SettingsGroup(scope, t("Switched"), t("Turned on and off."), listOf(switch, text), enabledBy = switch)
        settings.register(SettingsRegistration(switched))

        settings.get(switch) shouldBe false
        settings.get(text) shouldBe null
        settings.all(switched) shouldBe listOf(SettingValue(switch, false), SettingValue(text, "hello"))
        settings.effective(switched) shouldBe listOf(SettingValue(switch, false), SettingValue(text, "hello"))

        settings.set(switch, true) shouldBe null
        settings.get(text) shouldBe "hello"
    }

    @Test
    fun `a group is turned on by one of its own settings, all its settings are of its scope`() {
        shouldThrow<IllegalArgumentException> {
            SettingsGroup(SettingScope("other"), t("Other"), t("Turned on and off."), listOf(GREETING), enabledBy = FLAG)
        }
        // a setting of another scope
        shouldThrow<IllegalArgumentException> {
            SettingsGroup(SettingScope("other"), t("Other"), t("Settings to test with."), listOf(FLAG))
        }
    }

    @Test
    fun `a read-only setting answers the value given, it is neither stored nor changed`() {
        val scope = SettingScope("fixed")
        val flag = BooleanSetting(scope, SettingName("flag"), t("A flag"), t("Turns something on."), default = false)
        val fixed = TextSetting(scope, SettingName("fixed"), t("Fixed"), t("Given by the code."), default = "", readOnly = true)
        val group = SettingsGroup(scope, t("Fixed"), t("With a fixed value."), listOf(flag, fixed))
        var value = "first"
        settings.register(SettingsRegistration(group, readOnly = ReadOnlyValues().with(fixed) { value }))

        settings.get(fixed) shouldBe "first"
        value = "second"
        settings.all(group) shouldBe listOf(SettingValue(flag, false), SettingValue(fixed, "second"))
        settings.set(fixed, "third") shouldBe ErrorMessage(SettingsTexts.READ_ONLY, "label" to t("Fixed"))
        (settings.validate(group, mapOf("fixed" to JsonPrimitive("third"))) as Validation.Invalid).errors shouldBe
            listOf(ErrorMessage(SettingsTexts.READ_ONLY, "label" to t("Fixed")))
        // sent back as answered it changes nothing
        (settings.validate(group, mapOf("fixed" to JsonPrimitive("second"), "flag" to JsonPrimitive(true))) as Validation.Valid).values shouldBe
            listOf(SettingValue(flag, true))
        shouldThrow<IllegalArgumentException> { settings.set(listOf(SettingValue(fixed, "third")), owner = ADMIN) }
        stored(scope, fixed.name) shouldBe null
    }

    @Test
    fun `a group is registered once, with a value for each read-only setting`() {
        shouldThrow<IllegalArgumentException> { settings.register(SettingsRegistration(GROUP)) }
        val scope = SettingScope("fixed")
        val fixed = TextSetting(scope, SettingName("fixed"), t("Fixed"), t("Given by the code."), default = "", readOnly = true)
        shouldThrow<IllegalArgumentException> { settings.register(SettingsRegistration(SettingsGroup(scope, t("Fixed"), t("With a fixed value."), listOf(fixed)))) }
        shouldThrow<IllegalArgumentException> { ReadOnlyValues().with(FLAG) { true } }
    }

    @Test
    fun `a setting that requires another one is off and only goes back to its default while that one is off`() {
        val baseScope = SettingScope("base")
        val switch = BooleanSetting(baseScope, SettingName("enabled"), t("On"), t("Turns the base on."), default = false)
        val base = SettingsGroup(baseScope, t("Base"), t("Required."), listOf(switch))
        val dependentScope = SettingScope("dependent")
        val needs = BooleanSetting(dependentScope, SettingName("needs"), t("Needs"), t("Needs the base."), default = false, requires = switch)
        val text = TextSetting(dependentScope, SettingName("greeting"), t("A greeting"), t("Says hello."), default = "hello", maxLength = 10)
        val dependent = SettingsGroup(dependentScope, t("Dependent"), t("Requires."), listOf(needs, text))
        // what it requires is registered first
        shouldThrow<IllegalArgumentException> { settings.register(SettingsRegistration(dependent)) }
        settings.register(SettingsRegistration(base))
        settings.register(SettingsRegistration(dependent))
        val error = ErrorMessage(SettingsTexts.REQUIRES, "label" to t("Needs"), "required" to t("On"), "scope" to t("Base"))

        settings.get(needs) shouldBe null
        settings.get(text) shouldBe "hello"
        settings.unavailable(needs) shouldBe error
        settings.set(needs, true) shouldBe error
        // valid, but it does not count now (409)
        (settings.validate(dependent, mapOf("needs" to JsonPrimitive(true))) as Validation.Unavailable).errors shouldBe listOf(error)
        // sent back as answered it changes nothing
        (settings.validate(dependent, mapOf("needs" to JsonPrimitive(false))) as Validation.Valid).values shouldBe emptyList()

        settings.set(switch, true)
        settings.set(needs, true) shouldBe null
        settings.get(needs) shouldBe true

        settings.set(switch, false)
        settings.get(needs) shouldBe null
        settings.all(dependent) shouldBe listOf(SettingValue(needs, true), SettingValue(text, "hello"))
        settings.effective(dependent) shouldBe listOf(SettingValue(needs, false), SettingValue(text, "hello"))
        // back to its default also now
        (settings.validate(dependent, mapOf("needs" to JsonPrimitive(false))) as Validation.Valid).values shouldBe listOf(SettingValue(needs, false))
        settings.set(needs, false) shouldBe null
        settings.stored(needs) shouldBe false

        // a requirement needs its own requirement met
        val lastScope = SettingScope("last")
        val chained = BooleanSetting(lastScope, SettingName("chained"), t("Chained"), t("Needs needs."), default = true, requires = needs)
        settings.register(SettingsRegistration(SettingsGroup(lastScope, t("Last"), t("At the end."), listOf(chained))))
        settings.get(chained) shouldBe null
        settings.set(switch, true)
        settings.set(needs, true)
        settings.get(chained) shouldBe true
    }

    @Test
    fun `the check of a group sees the values as the change leaves them`() {
        val scope = SettingScope("checked")
        val on = BooleanSetting(scope, SettingName("on"), t("On"), t("Turns something on."), default = false)
        val key = TextSetting(scope, SettingName("key"), t("A text"), t("Says something."), default = "")
        val group = SettingsGroup(scope, t("Base"), t("Required."), listOf(on, key))
        val seen = mutableListOf<Triple<Boolean, String?, String>>()
        settings.register(SettingsRegistration(group, check = { values ->
            seen += Triple(values[on], values.new(key), values.stored(key))
            if (values[on] && values[key].isEmpty()) listOf(ErrorMessage(t("Required."))) else emptyList()
        }))

        settings.set(on, true) shouldBe ErrorMessage(t("Required."))
        settings.validate(group, mapOf("on" to JsonPrimitive(true))) shouldBe Validation.Invalid(listOf(ErrorMessage(t("Required."))))
        settings.validate(group, mapOf("on" to JsonPrimitive(true), "key" to JsonPrimitive("k"))).shouldBeInstanceOf<Validation.Valid>()
        seen.last() shouldBe Triple(true, "k", "")
        settings.get(on) shouldBe false
    }

    @Test
    fun `a masked value sent back as the api answered it keeps the stored one`() {
        val scope = SettingScope("masked")
        val secret = TextSetting(scope, SettingName("secret"), t("Secret"), t("Hides itself."), default = "", mask = 2)
        val group = SettingsGroup(scope, t("Masked"), t("Hides."), listOf(secret))
        settings.register(SettingsRegistration(group))
        settings.set(secret, "the secret") shouldBe null

        settings.validate(group, mapOf("secret" to JsonPrimitive("th••••••••"))).shouldBeInstanceOf<Validation.Valid>().values shouldBe emptyList()
        settings.get(secret) shouldBe "the secret"
    }

    @Test
    fun `a stored value the setting does not read is its default`() {
        repository.replace(TEST.value, mapOf(FLAG.name.value to "maybe"), owner = ADMIN)
        settings.get(FLAG) shouldBe false
    }

    @Test
    fun `a choice answers its default until one is set, also for a stored value that is no choice any more`() {
        val scope = SettingScope("chosen")
        val choice =
            ChoiceSetting(scope, SettingName("choice"), t("A choice"), t("Takes one."), listOf(SettingChoice("first", t("First")), SettingChoice("second", t("Second"))), default = "first")
        val group = SettingsGroup(scope, t("Test"), t("Settings to test with."), listOf(choice))
        settings.register(SettingsRegistration(group))

        settings.get(choice) shouldBe "first"
        (settings.validate(group, mapOf("choice" to JsonPrimitive("third"))) as Validation.Invalid).errors shouldBe
            listOf(ErrorMessage(SettingsTexts.NOT_CHOICE, "label" to t("A choice")))
        val valid = settings.validate(group, mapOf("choice" to JsonPrimitive("second"))).shouldBeInstanceOf<Validation.Valid>()
        settings.set(valid.values, owner = ADMIN)
        settings.get(choice) shouldBe "second"

        repository.replace(scope.value, mapOf(choice.name.value to "third"), owner = ADMIN)
        settings.get(choice) shouldBe "first"
    }

    @Test
    fun `validates json values, every problem at once, and sets them in one go`() {
        (
            settings.validate(
                GROUP,
                mapOf("flag" to JsonPrimitive("true"), "greeting" to JsonPrimitive("a greeting far too long"), "other" to JsonPrimitive(1)),
            ) as Validation.Invalid
        ).errors shouldBe
            listOf(
                ErrorMessage(SettingsTexts.NOT_BOOLEAN, "label" to t("A flag")),
                ErrorMessage(SettingsTexts.TOO_LONG, "label" to t("A greeting"), "max" to 10),
                ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to "other", "scope" to TEST),
            )

        val valid = settings.validate(GROUP, mapOf("flag" to JsonPrimitive(true))).shouldBeInstanceOf<Validation.Valid>()
        settings.set(valid.values, owner = ADMIN)
        settings.get(FLAG) shouldBe true
        settings.get(GREETING) shouldBe "hello"
    }

    @Test
    fun `updates the settings named of a group and answers all of them, masked, nothing when one is not valid`() {
        val scope = SettingScope("secret")
        val flag = BooleanSetting(scope, SettingName("flag"), t("A flag"), t("Turns something on."), default = false)
        val secret = TextSetting(scope, SettingName("secret"), t("A secret"), t("Hides itself."), default = "", mask = 2)
        val group = SettingsGroup(scope, t("Secret"), t("Hides."), listOf(flag, secret))
        settings.register(SettingsRegistration(group))

        settings.update(group, mapOf("flag" to JsonPrimitive(true), "other" to JsonPrimitive(true)), owner = ADMIN) shouldBe
            SettingsUpdateResult.Invalid(listOf(ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to "other", "scope" to scope)))
        settings.get(flag) shouldBe false
        (settings.update(group, mapOf("flag" to JsonPrimitive(true), "secret" to JsonPrimitive("the secret")), owner = ADMIN) as SettingsUpdateResult.Updated)
            .values.json() shouldBe mapOf("flag" to JsonPrimitive(true), "secret" to JsonPrimitive("th••••••••"))
        settings.get(secret) shouldBe "the secret"
    }

    @Test
    fun `the groups are served in the order they are registered, by their level`() {
        val users = SettingScope("users")
        val text = TextSetting(users, SettingName("text"), t("A text"), t("Says something."), default = "", level = SettingLevel.USER)
        settings.register(SettingsRegistration(SettingsGroup(users, t("Other"), null, listOf(text))))

        settings.groups.map { it.name } shouldBe listOf(TEST, users)
        settings.groups(SettingLevel.INSTANCE).map { it.name } shouldBe listOf(TEST)
        settings.groups(SettingLevel.USER).map { it.name } shouldBe listOf(users)
        settings.group("test", SettingLevel.INSTANCE) shouldBe GROUP
        settings.group("test", SettingLevel.USER) shouldBe null
        settings.group("users", SettingLevel.INSTANCE) shouldBe null
    }

    @Test
    fun `values of more than one scope or not valid are a mistake`() {
        val other = TextSetting(SettingScope("other"), SettingName("value"), t("A text"), t("Says something."), default = "")
        shouldThrow<IllegalArgumentException> { settings.set(listOf(SettingValue(FLAG, true), SettingValue(other, "x")), owner = ADMIN) }
        shouldThrow<IllegalArgumentException> { settings.set(listOf(SettingValue(GREETING, "a greeting far too long")), owner = ADMIN) }
    }

    @Test
    fun `a setting the backend makes for itself is created once`() {
        val key = TextSetting(SystemSettings.SCOPE, SettingName("A_KEY"), t("A text"), t("Says something."), default = "", maxLength = 1000)
        var created = 0

        val first = settings.getOrCreate(key) { "key ${++created}" }
        settings.getOrCreate(key) { "key ${++created}" } shouldBe first
        created shouldBe 1
        settings.stored(key) shouldBe first
        stored(SystemSettings.SCOPE, key.name) shouldBe first
    }
}
