package io.pelle.sak.settings

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.api.installApi
import io.pelle.sak.auth.Role
import io.pelle.sak.auth.SESSION_AUTH
import io.pelle.sak.auth.SessionPrincipal
import io.pelle.sak.auth.requireRole
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.settings.model.internal.BooleanSetting
import io.pelle.sak.settings.model.internal.ChoiceSetting
import io.pelle.sak.settings.model.internal.SettingChoice
import io.pelle.sak.settings.model.internal.SettingLevel
import io.pelle.sak.settings.model.internal.SettingName
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.model.internal.SettingsGroup
import io.pelle.sak.settings.model.internal.TextSetting
import io.pelle.sak.settings.user.userSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

private const val ADMIN = "admin@example.org"
private const val ANNA = "anna@example.org"
private const val BERT = "bert@example.org"

// the user of a request of the tests and their role; none for a request without a session
private const val USER_HEADER = "X-Test-User"
private const val ROLE_HEADER = "X-Test-Role"

private val BASE = SettingScope("base")
private val SWITCH = BooleanSetting(BASE, SettingName("enabled"), t("On"), t("Turns the base on."), default = false)
private val SECRET = TextSetting(BASE, SettingName("secret"), t("A secret"), t("Hides itself."), default = "", mask = 2)
private val CHOICE =
    ChoiceSetting(BASE, SettingName("choice"), t("A choice"), t("Takes one."), listOf(SettingChoice("first", t("First")), SettingChoice("second", t("Second"))), default = "first")
private val BASE_GROUP = SettingsGroup(BASE, t("Base"), t("Settings to test with."), listOf(SWITCH, SECRET, CHOICE))

private val DEPENDENT = SettingScope("dependent")
private val NEEDS = BooleanSetting(DEPENDENT, SettingName("needs"), t("Needs"), t("Needs the base."), default = false, requires = SWITCH)
private val DEPENDENT_GROUP = SettingsGroup(DEPENDENT, t("Dependent"), t("Requires."), listOf(NEEDS))

// the api of the admins (/settings) and of the users (/user/settings), mounted as the backend mounts them
// (SakApps.kt): behind the session, the one of the admins for the admins only. Every endpoint, what it
// answers and who reaches it (SET-4)
class SettingsRoutesTest {
    @TempDir
    lateinit var tempDir: Path

    // whether the AI of the users counts now
    private var setUp = true

    private val app = SettingScope("app")
    private val ai =
        BooleanSetting(
            app,
            SettingName("ai"),
            t("A flag"),
            t("Greets %{name}."),
            default = false,
            level = SettingLevel.USER,
            unavailable = { if (setUp) null else ErrorMessage(t("Required.")) },
            descriptionArgs = { mapOf("name" to "the model") },
        )
    private val appGroup = SettingsGroup(app, t("Other"), null, listOf(ai))

    private val settings by lazy {
        SettingService(SakDatabase.open(tempDir.resolve("sak.db"))).also {
            it.register(SettingsRegistration(BASE_GROUP))
            it.register(SettingsRegistration(DEPENDENT_GROUP))
            it.register(SettingsRegistration(appGroup))
        }
    }

    private fun routes(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(Authentication) {
                    provider(SESSION_AUTH) {
                        authenticate { context ->
                            val user = context.call.request.headers[USER_HEADER] ?: return@authenticate
                            val role = Role.valueOf(context.call.request.headers[ROLE_HEADER] ?: "ADMIN")
                            context.principal(SessionPrincipal(user, Instant.MAX, setOf(role)))
                        }
                    }
                }
                routing {
                    installApi()
                    authenticate(SESSION_AUTH) {
                        route("/settings") {
                            requireRole(Role.ADMIN)
                            settings(this@SettingsRoutesTest.settings)
                        }
                        route("/user/settings") {
                            requireRole(Role.ADMIN, Role.USER)
                            userSettings(this@SettingsRoutesTest.settings)
                        }
                    }
                }
            }
            block()
        }

    private suspend fun ApplicationTestBuilder.get(
        path: String,
        user: String? = ADMIN,
        role: Role = Role.ADMIN,
    ): HttpResponse =
        client.get(path) {
            user?.let { header(USER_HEADER, it) }
            header(ROLE_HEADER, role.name)
        }

    private suspend fun ApplicationTestBuilder.put(
        path: String,
        body: String,
        user: String? = ADMIN,
        role: Role = Role.ADMIN,
    ): HttpResponse =
        client.put(path) {
            user?.let { header(USER_HEADER, it) }
            header(ROLE_HEADER, role.name)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `no endpoint answers without a session, the ones of the admins not to a user`() =
        routes {
            listOf("/settings", "/settings/base", "/user/settings").forEach { get(it, user = null).status shouldBe HttpStatusCode.Unauthorized }
            listOf("/settings/base" to """{"enabled":true}""", "/user/settings/app" to """{"ai":true}""").forEach { (path, body) ->
                put(path, body, user = null).status shouldBe HttpStatusCode.Unauthorized
            }
            get("/settings", ANNA, Role.USER).status shouldBe HttpStatusCode.Forbidden
            get("/settings/base", ANNA, Role.USER).status shouldBe HttpStatusCode.Forbidden
            put("/settings/base", """{"enabled":true}""", ANNA, Role.USER).status shouldBe HttpStatusCode.Forbidden
            // nothing changed
            settings.stored(SWITCH) shouldBe false
            // the settings of the users for every role
            get("/user/settings", ANNA, Role.USER).status shouldBe HttpStatusCode.OK
            put("/user/settings/app", """{"ai":true}""", ANNA, Role.USER).status shouldBe HttpStatusCode.OK
            get("/user/settings", ADMIN, Role.ADMIN).status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `the admins get the groups of the instance with their settings, a page of them`() =
        routes {
            val page = get("/settings").json()
            page.getValue("pagination").jsonObject.string("total") shouldBe "2"
            val groups = page.getValue("items").jsonArray.map { it.jsonObject }
            // the group of the users only is no group of the admins
            groups.map { it.string("name") } shouldBe listOf("base", "dependent")
            val base = groups.first()
            base.string("label") shouldBe "Base"
            base.string("description") shouldBe "Settings to test with."
            val (enabled, secret, choice) = base.getValue("settings").jsonArray.map { it.jsonObject }
            enabled.string("type") shouldBe "boolean"
            enabled.string("default") shouldBe "false"
            secret.string("type") shouldBe "text"
            secret.string("mask") shouldBe "2"
            choice.getValue("choices").toString() shouldBe """[{"value":"first","label":"First"},{"value":"second","label":"Second"}]"""
            groups[1].getValue("settings").jsonArray.single().jsonObject.getValue("requires").toString() shouldBe
                """{"scope":"base","setting":"enabled"}"""
            get("/settings?offset=1&limit=1").json().getValue("items").jsonArray.single().jsonObject.string("name") shouldBe "dependent"
        }

    @Test
    fun `the admins read the values of a group, masked, as stored or as the code sees them`() =
        routes {
            settings.set(SECRET, "the secret")
            settings.set(NEEDS, true) shouldBe ErrorMessage(SettingsTexts.REQUIRES, "label" to t("Needs"), "required" to t("On"), "scope" to t("Base"))
            get("/settings/base").bodyAsText() shouldBe """{"enabled":false,"secret":"th••••••••","choice":"first"}"""
            settings.set(SWITCH, true)
            settings.set(NEEDS, true)
            settings.set(SWITCH, false)
            get("/settings/dependent").bodyAsText() shouldBe """{"needs":true}"""
            get("/settings/dependent?effective=true").bodyAsText() shouldBe """{"needs":false}"""
            get("/settings/none").status shouldBe HttpStatusCode.NotFound
            // a group of the users only is none of the admins
            get("/settings/app").status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `the admins change the values of a group, every value checked first`() =
        routes {
            put("/settings/base", """{"enabled":true,"secret":"the secret"}""").let {
                it.status shouldBe HttpStatusCode.OK
                it.bodyAsText() shouldBe """{"enabled":true,"secret":"th••••••••","choice":"first"}"""
            }
            settings.get(SECRET) shouldBe "the secret"
            put("/settings/base", """{"enabled":"yes","choice":"third","other":1}""").let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.bodyAsText() shouldBe
                    """{"errors":[{"code":"settings.not_boolean","message":"\"On\" must be true or false."},""" +
                    """{"code":"settings.not_choice","message":"\"A choice\" must be one of its choices."},""" +
                    """{"code":"settings.unknown_setting","message":"There is no setting \"other\" in \"base\"."}]}"""
            }
            put("/settings/base", "[]").status shouldBe HttpStatusCode.BadRequest
            put("/settings/none", """{"enabled":true}""").status shouldBe HttpStatusCode.NotFound
            put("/settings/app", """{"ai":true}""").status shouldBe HttpStatusCode.NotFound
            // valid, but its requirement is off
            settings.set(SWITCH, false)
            put("/settings/dependent", """{"needs":true}""").let {
                it.status shouldBe HttpStatusCode.Conflict
                it.bodyAsText() shouldBe """{"errors":[{"code":"settings.requires","message":"\"Needs\" needs \"On\" of \"Base\" turned on."}]}"""
            }
            settings.stored(NEEDS) shouldBe false
        }

    @Test
    fun `a user gets their own settings, with the description in full and whether each counts`() =
        routes {
            settings.set(ai, true, owner = ANNA, user = ANNA)
            val anna = get("/user/settings", ANNA, Role.USER).json().getValue("scopes").jsonArray.single().jsonObject
            anna.string("name") shouldBe "app"
            anna.string("label") shouldBe "Other"
            anna.getValue("settings").jsonArray.single().jsonObject.let {
                it.string("name") shouldBe "ai"
                it.string("label") shouldBe "A flag"
                // the placeholders of the description filled (SET-5)
                it.string("description") shouldBe "Greets the model."
                it.string("type") shouldBe "boolean"
                it.string("default") shouldBe "false"
                it.string("value") shouldBe "true"
                it.string("available") shouldBe "true"
            }
            // another user has their own value
            get("/user/settings", BERT, Role.USER).json().getValue("scopes").jsonArray.single().jsonObject
                .getValue("settings").jsonArray.single().jsonObject.string("value") shouldBe "false"

            setUp = false
            get("/user/settings", ANNA, Role.USER).json().getValue("scopes").jsonArray.single().jsonObject
                .getValue("settings").jsonArray.single().jsonObject.let {
                    it.string("value") shouldBe "true"
                    it.string("available") shouldBe "false"
                    it.string("unavailableReason") shouldBe "Required."
                }
        }

    @Test
    fun `a user changes their own settings of a group, nothing of the instance or of others`() =
        routes {
            put("/user/settings/app", """{"ai":true}""", ANNA, Role.USER).let {
                it.status shouldBe HttpStatusCode.OK
                val group = it.json()
                group.string("name") shouldBe "app"
                group.getValue("settings").jsonArray.single().jsonObject.string("value") shouldBe "true"
            }
            settings.on(ai, ANNA) shouldBe true
            settings.on(ai, BERT) shouldBe false
            put("/user/settings/app", """{"ai":"yes"}""", ANNA, Role.USER).let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.bodyAsText() shouldBe """{"errors":[{"code":"settings.not_boolean","message":"\"A flag\" must be true or false."}]}"""
            }
            // a group of the instance is none of the users, its settings are no settings of theirs
            put("/user/settings/base", """{"enabled":true}""", ANNA, Role.USER).status shouldBe HttpStatusCode.NotFound
            put("/user/settings/none", """{"ai":true}""", ANNA, Role.USER).status shouldBe HttpStatusCode.NotFound
            settings.stored(SWITCH) shouldBe false
            // does not count now: not turned on, but off again
            setUp = false
            put("/user/settings/app", """{"ai":true}""", BERT, Role.USER).let {
                it.status shouldBe HttpStatusCode.Conflict
                it.bodyAsText() shouldBe """{"errors":[{"code":"settingstest.required","message":"Required."}]}"""
            }
            put("/user/settings/app", """{"ai":false}""", ANNA, Role.USER).status shouldBe HttpStatusCode.OK
            settings.stored(ai, ANNA) shouldBe false
        }
}
