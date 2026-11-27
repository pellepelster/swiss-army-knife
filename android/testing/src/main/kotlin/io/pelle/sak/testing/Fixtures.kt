package io.pelle.sak.testing

import io.pelle.sak.api.model.ApiErrorMessage
import io.pelle.sak.api.model.ApiErrorResponse
import io.pelle.sak.api.model.ExchangeResponse
import io.pelle.sak.api.model.FileResponse
import io.pelle.sak.api.model.InfoResponse
import io.pelle.sak.api.model.UserSettingResponse
import io.pelle.sak.api.model.UserSettingScopeResponse
import io.pelle.sak.api.model.UserSettingsResponse
import io.pelle.sak.api.model.WhoAmIResponse
import io.pelle.sak.core.api.ApiJson
import io.pelle.sak.core.session.Stored
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

// the answers of the fake backend are built of the generated classes of the api and encoded as the
// backend does, so the compiler checks every field the schema requires

// the SAK address of the deployment of the tests, whose api is URL
const val ADDRESS = "https://sak.example.org"

// the deployment of the tests, no session
fun signedOut() = Stored(apiUrl = URL, address = ADDRESS)

// a session of the deployment of the tests until a time
fun signedIn(
    until: Instant,
    token: String = "t",
    email: String = "admin",
) = Stored(apiUrl = URL, address = ADDRESS, token = token, email = email, expiresAt = until)

// a body of the api as json
inline fun <reified T> json(value: T): String = ApiJson.encodeToString(value)

// what GET /info answers: a deployment of many users, of a version or of none
fun info(
    version: String? = null,
    userMode: InfoResponse.UserMode = InfoResponse.UserMode.MULTI,
) = json(InfoResponse("family", InfoResponse.DnsMode.DOMAIN, userMode, version))

// what a sign-in answers
fun session(
    token: String,
    email: String,
    expiresAt: String = "2026-12-02T08:00:00.000Z",
) = json(ExchangeResponse(token, email, expiresAt, listOf("admin"), "/"))

// what GET /auth/who-am-i answers
fun whoAmI(
    username: String,
    name: String? = null,
) = json(WhoAmIResponse(username, listOf("user"), name))

// a file stored by POST /files
fun file(
    id: Int,
    contentType: String = "image/jpeg",
) = json(FileResponse(id, contentType, 1, "/api/v1/files/$id", "/api/v1/files/$id/thumbnail", "2026-10-30T08:00:00.000Z"))

// an error of the backend with its message
fun refused(
    message: String,
    code: String = "test.refused",
) = json(ApiErrorResponse(listOf(ApiErrorMessage(code, message))))

// the switch of the AI of an app among the settings of the user; not available while no AI is set up
fun aiSetting(
    value: Boolean,
    available: Boolean = true,
) = UserSettingResponse(
    "ai",
    "Use AI",
    "It is sent to OpenRouter (model vision/model), which is asked not to keep it.",
    UserSettingResponse.Type.BOOLEAN,
    JsonPrimitive(false),
    JsonPrimitive(value),
    available,
    if (available) null else "No AI is set up for this SAK instance.",
)

fun userScope(
    name: String,
    label: String,
    vararg settings: UserSettingResponse,
) = UserSettingScopeResponse(name, label, settings.toList())

// the answer of GET /user/settings
fun userSettings(vararg scopes: UserSettingScopeResponse) = json(UserSettingsResponse(scopes.toList()))

// an event of the stream of the changes of the lists: the list changed
fun changed(listId: Int) = "event: changed\ndata: {\"listId\":$listId}\n\n"

// a connect token of the QR code of the web app as the app reads it: the address of its user in the
// payload, which the app shows before the sign-in (SEC-49); the signature is none, the fake backend checks none
fun connectToken(email: String = "anna@example.org"): String =
    "eyJhbGciOiJFUzI1NiJ9." +
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$email"}""".toByteArray()) +
        ".c2lnbmF0dXJl"
