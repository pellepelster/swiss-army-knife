package io.pelle.sak.e2e.local

import com.microsoft.playwright.APIRequest
import com.microsoft.playwright.APIRequestContext
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.RequestOptions
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

// the admin of config/local-config.yaml and the local admin password (SAK_LOCAL_ADMIN_PASSWORD_HASH
// of mise.toml)
const val ADMIN = "admin@localhost"
const val ADMIN_PASSWORD = "a password of sixteen"

// the notes shown on the notes page, one of each color; the run removes them again before it
// creates them, other notes of the local environment stay
val NOTES =
    listOf(
        "Buy oat milk, coffee and two lemons" to "yellow",
        "Call the dentist about the appointment on Friday" to "pink",
        "Ideas for the garden: tomatoes, basil, a bench under the apple tree" to "green",
        "Read: Designing Data-Intensive Applications" to "blue",
        "Book the train to Hamburg" to "orange",
    )
const val NEW_NOTE = "Pick up the parcel at the post office"
const val PHOTO_NOTE = "The view from the hill last night"
const val RECORDING_NOTE = "Voice memo: what to say in the talk on Monday"

// a bucket and two redirects, kept from run to run: the name of a deleted bucket stays taken
const val BUCKET = "docs-site"
const val BUCKET_DOMAIN = "docs.example.org"
val REDIRECTS = listOf("old.example.org" to "example.org", "www.example.org" to "example.org")

// the data of the local environment the screenshots and the videos show, the same for both: the
// admin signed in once with the password, the notes, a bucket and two redirects
class LocalData(
    private val playwright: Playwright,
) {
    // the session of the admin, what a signed in page keeps in its local storage
    val session: JsonObject = passwordLogin()

    val api: APIRequestContext =
        playwright.request().newContext(
            APIRequest
                .NewContextOptions()
                .setBaseURL(FRONTEND_URL)
                .setExtraHTTPHeaders(mapOf("Authorization" to "Bearer ${session.getValue("sessionToken").jsonPrimitive.content}")),
        )

    // the pages of the context start signed in as the admin
    fun signIn(context: BrowserContext) {
        val stored =
            buildJsonObject {
                put("token", session.getValue("sessionToken").jsonPrimitive.content)
                put("email", session.getValue("email").jsonPrimitive.content)
                put("expiresAt", session.getValue("expiresAt").jsonPrimitive.content)
                put("roles", session.getValue("roles"))
            }
        // the session as a string literal of javascript
        val literal = Json.encodeToString(String.serializer(), stored.toString())
        context.addInitScript("localStorage.setItem('sak.session', $literal)")
    }

    // one sign in with the password for the data of the run, the pages sign in with it too
    private fun passwordLogin(): JsonObject {
        val request = playwright.request().newContext(APIRequest.NewContextOptions().setBaseURL(FRONTEND_URL))
        val answer =
            request.post(
                "/api/v1/auth/login/password",
                RequestOptions.create().setData(mapOf("password" to ADMIN_PASSWORD, "redirect" to "/")),
            )
        check(answer.ok()) { "the password login answered ${answer.status()}, is mise run local:run up?" }
        return Json.parseToJsonElement(answer.text()).jsonObject.also { request.dispose() }
    }

    private fun json(response: com.microsoft.playwright.APIResponse) = Json.parseToJsonElement(response.text())

    // the notes, the bucket and the redirects the pages show
    fun seed() {
        seedNotes()
        if (api.get("/api/v1/static-hosting/$BUCKET").status() == 404) {
            api.post("/api/v1/static-hosting", RequestOptions.create().setData(mapOf("name" to BUCKET, "domains" to listOf(BUCKET_DOMAIN))))
        }
        REDIRECTS.forEach { (source, target) ->
            if (api.get("/api/v1/redirects/$source").status() == 404) {
                api.post(
                    "/api/v1/redirects",
                    RequestOptions.create().setData(mapOf("sourceDomain" to source, "target" to mapOf("type" to "domain", "domain" to target))),
                )
            }
        }
    }

    // the notes of NOTES alone: those of a run before and empty ones go, other notes stay
    fun seedNotes() {
        val texts = NOTES.map { it.first }.toSet() + NEW_NOTE + PHOTO_NOTE + RECORDING_NOTE
        json(api.get("/api/v1/notes?limit=100"))
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("text").jsonPrimitive.content in texts || it.getValue("text").jsonPrimitive.content.isEmpty() }
            .forEach { api.delete("/api/v1/notes/${it.getValue("id").jsonPrimitive.content}") }
        NOTES.reversed().forEach { (text, color) ->
            val created =
                api.post(
                    "/api/v1/notes",
                    RequestOptions.create().setData(
                        buildJsonObject {
                            put("text", text)
                            put("color", color)
                        }.toString(),
                    ).setHeader("Content-Type", "application/json"),
                )
            check(created.ok()) { "a note could not be created: ${created.status()} ${created.text()}" }
        }
    }

    // a photo for the note: an evening sky over hills, drawn once per run
    fun photo(): Path {
        val file = Files.createTempFile("sak-photo", ".png")
        val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paint = GradientPaint(0f, 0f, Color(0xF9, 0xA8, 0x5D), 0f, 600f, Color(0x6A, 0x4C, 0x93))
            fillRect(0, 0, 1200, 900)
            color = Color(0xFF, 0xE0, 0x8A)
            fillOval(760, 260, 180, 180)
            color = Color(0x3B, 0x5B, 0x4F)
            fillOval(-200, 560, 900, 600)
            color = Color(0x2E, 0x48, 0x3F)
            fillOval(400, 600, 1100, 600)
            dispose()
        }
        ImageIO.write(image, "png", file.toFile())
        return file
    }
}
