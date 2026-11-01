package io.pelle.sak.i18n

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GeneratorTest {
    private fun files(yaml: String) = Generator(Texts.parse(yaml.trimIndent())).files()

    private fun problems(yaml: String) = shouldThrow<TextsException> { files(yaml) }.problems

    @Test
    fun `the files of every platform are up to date with texts yml`() {
        val root = Path.of(System.getProperty("sak.root") ?: error("sak.root is not set"))
        // run mise run i18n:generate
        Generator.outdated(root).shouldBeEmpty()
    }

    @Test
    fun `a platform reads common and its part, the platform drops out of the key`() {
        val files =
            files(
                """
                common:
                  shared: { save: { en: "Save", de: "Speichern" } }
                backend:
                  api: { malformed: { en: "Malformed", de: "Fehlerhaft" } }
                notes:
                  common: { photo_removed: { en: "Photo removed", de: "Foto entfernt" } }
                  backend: { too_many: { en: "At most %{max}", de: "Höchstens %{max}" } }
                  web: { page: { new: { en: "New note", de: "Neue Notiz" } } }
                """,
            )
        files.getValue("$BACKEND_DIR/notes.de.yml") shouldContain "photo_removed: \"Foto entfernt\"\ntoo_many: \"Höchstens %{max}\"\n"
        files.getValue("$BACKEND_DIR/api.en.yml") shouldContain "malformed: \"Malformed\""
        files.getValue("$BACKEND_DIR/shared.en.yml") shouldContain "save: \"Save\""
        val web = files.getValue(WEB_FILE)
        web shouldContain "notes: {\n      photo_removed: 'Photo removed',\n      page: {\n        new: 'New note',"
        web shouldNotContain "too_many"
        web shouldNotContain "Malformed"
        // notes has no android part
        files.getValue("$ANDROID_RES/values/strings.xml") shouldNotContain "notes_"
    }

    @Test
    fun `sakctl reads the three parts of ctl below their names and nothing else`() {
        val files =
            files(
                """
                common:
                  shared: { save: { en: "Save", de: "Speichern" } }
                ctl:
                  common: { done: { en: "Done", de: "Fertig" } }
                  cli: { hint: { en: "Give --name", de: "Gib --name an" } }
                  ui: { quit: { en: "Quit", de: "Beenden" } }
                """,
            )
        files.getValue("$CTL_DIR/de.yml") shouldContain "common:\n  done: \"Fertig\"\ncli:\n  hint: \"Gib --name an\"\nui:\n  quit: \"Beenden\"\n"
        files.getValue("$CTL_DIR/en.yml") shouldNotContain "Save"
    }

    @Test
    fun `android names a text by its path, numbers its placeholders and has plurals`() {
        val files =
            files(
                """
                android:
                  app_name: "SAK Grocery List"
                  error: { moved: { en: "%{from} is now at <%{to}> & 100%", de: "%{to} ist jetzt das neue %{from}" } }
                grocery:
                  android:
                    bought: { en: "\"%{item}\" bought", de: "'%{item}' gekauft" }
                    to_buy:
                      en: { one: "%{count} item to buy", other: "%{count} items to buy" }
                      de: { one: "%{count} Artikel zu kaufen", other: "%{count} Artikel zu kaufen" }
                """,
            )
        val english = files.getValue("$ANDROID_RES/values/strings.xml")
        english shouldContain """<string name="app_name" translatable="false">SAK Grocery List</string>"""
        english shouldContain """<string name="error_moved">%1${'$'}s is now at &lt;%2${'$'}s&gt; &amp; 100%%</string>"""
        english shouldContain """<string name="grocery_bought">\"%1${'$'}s\" bought</string>"""
        english shouldContain """<item quantity="other">%1${'$'}d items to buy</item>"""
        val german = files.getValue("$ANDROID_RES/values-de/strings.xml")
        german shouldNotContain "app_name"
        german shouldContain """<string name="error_moved">%2${'$'}s ist jetzt das neue %1${'$'}s</string>"""
        german shouldContain """<string name="grocery_bought">\'%1${'$'}s\' gekauft</string>"""
    }

    @Test
    fun `a text of lines is a block in yaml`() {
        val files = files("backend:\n  auth:\n    mail:\n      body:\n        en: |\n          Hello,\n\n          bye\n        de: |\n          Hallo,\n\n          tschüss\n")
        files.getValue("$BACKEND_DIR/auth.de.yml") shouldContain "body: |-\n    Hallo,\n\n    tschüss\n"
    }

    @Test
    fun `a text needs every language with the same placeholders`() {
        val problems =
            problems(
                """
                web:
                  a: { en: "A" }
                  b: { en: "B %{name}", de: "B %{other}" }
                  c: { en: "C", de: "C", fr: "C" }
                  d: { en: yes, de: "Ja" }
                """,
            )
        problems shouldContain "web.a has no de"
        problems shouldContain "web.b in de has the placeholders [other], english [name]"
        problems shouldContain "web.c has fr, no language of en, de"
        problems shouldContain "web.d is a text in one language and a plural or no text in another"
    }

    @Test
    fun `a key is in one part of a section only`() {
        problems(
            """
            notes:
              common: { title: { en: "Notes", de: "Notizen" } }
              web: { title: { en: "All notes", de: "Alle Notizen" } }
            """,
        ) shouldBe listOf("notes.title is in notes.common and notes.web")
    }

    @Test
    fun `a section has the parts of its platforms only`() {
        problems(
            """
            notes:
              ios: { title: { en: "Notes", de: "Notizen" } }
            ctl:
              web: { title: { en: "Notes", de: "Notizen" } }
            """,
        ) shouldBe
            listOf(
                "notes.ios is no part of a section, they are common, backend, web, android",
                "ctl.web is no part of a section, they are common, cli, ui",
            )
    }

    @Test
    fun `only android has plurals and the backend no dollar`() {
        val problems =
            problems(
                """
                backend:
                  logs:
                    price: { en: "costs $1", de: "kostet $1" }
                    count:
                      en: { one: "one", other: "%{count}" }
                      de: { one: "eins", other: "%{count}" }
                """,
            )
        problems shouldContain "logs.price has a \$, the backend reads it as a variable of the environment"
        problems shouldContain "logs.count is a plural, backend has none: only android has plurals"
    }

    @Test
    fun `two keys are not the same name on android`() {
        problems(
            """
            android:
              error_gone: { en: "Gone", de: "Weg" }
              error: { gone: { en: "Gone", de: "Weg" } }
            """,
        ) shouldBe listOf("error.gone and error_gone are both error_gone on android")
    }
}
