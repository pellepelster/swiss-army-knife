package io.pelle.sak.api

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import io.pelle.sak.api.model.ApiErrorMessage
import io.pelle.sak.api.model.ApiErrorResponse
import io.pelle.sak.api.testing.catalogProblems
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.model.PaginationResponse
import io.kotest.matchers.string.shouldNotContain

class ApiTest {
    @Serializable
    data class Request(
        val name: String,
    )

    private fun test(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                installUnexpectedErrors()
                routing {
                    // outside of the api, without content negotiation
                    get("/plain/boom") { error("the secret detail of the plain route") }
                    post("/plain/large") { throw PayloadTooLargeException(16) }
                    route("/api") {
                        install(ContentNegotiation) { json() }
                        install(ApiErrors)
                        get("/numbers") {
                            val page = call.pageRequest() ?: return@get
                            call.respond(page.of(page.of((1..120).toList())))
                        }
                        get("/boom") { error("the secret detail of the api") }
                        // what RequestBodyLimit throws
                        post("/large") { throw PayloadTooLargeException(16) }
                        post("/names") {
                            val request = call.receive<Request>()
                            if (request.name.isBlank()) call.respondErrors(Text(TestTexts.BLANK, "field" to "name")) else call.respond(request)
                        }
                    }
                }
            }
            block()
        }

    @Test
    fun `answers lists in pages`() =
        test {
            Json.decodeFromString<PageResponse<Int>>(client.get("/api/numbers").bodyAsText()).let {
                it.items shouldBe (1..50).toList()
                it.pagination shouldBe PaginationResponse(0, 50, 120)
            }
            Json.decodeFromString<PageResponse<Int>>(client.get("/api/numbers?offset=100&limit=30").bodyAsText()).let {
                it.items shouldBe (101..120).toList()
                it.pagination shouldBe PaginationResponse(100, 30, 120)
            }
            Json.decodeFromString<PageResponse<Int>>(client.get("/api/numbers?offset=200").bodyAsText()).items shouldBe emptyList()
        }

    @Test
    fun `answers invalid pages and requests with errors`() =
        test {
            val invalid = client.get("/api/numbers?offset=-1&limit=501")
            invalid.status shouldBe HttpStatusCode.BadRequest
            Json.decodeFromString<ApiErrorResponse>(invalid.bodyAsText()).errors.size shouldBe 2
            client.get("/api/numbers?limit=x").status shouldBe HttpStatusCode.BadRequest

            val blank =
                client.post("/api/names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":" "}""")
                }
            blank.status shouldBe HttpStatusCode.BadRequest
            Json.decodeFromString<ApiErrorResponse>(blank.bodyAsText()) shouldBe error("apitest.blank", "name must not be blank.")

            // in the language of the user, the code stays
            val german =
                client.post("/api/names") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.AcceptLanguage, "de-DE,de;q=0.9,en;q=0.8")
                    setBody("""{"name":" "}""")
                }
            Json.decodeFromString<ApiErrorResponse>(german.bodyAsText()) shouldBe error("apitest.blank", "name darf nicht leer sein.")

            val malformed =
                client.post("/api/names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"nam""")
                }
            malformed.status shouldBe HttpStatusCode.BadRequest
            Json.decodeFromString<ApiErrorResponse>(malformed.bodyAsText()) shouldBe error("api.malformed_request", "The request is malformed.")
        }

    @Test
    fun `answers a failure no route answered with a 500 that tells nothing of it`() =
        test {
            for (path in listOf("/api/boom", "/plain/boom")) {
                val failed = client.get(path)
                failed.status shouldBe HttpStatusCode.InternalServerError
                Json.decodeFromString<ApiErrorResponse>(failed.bodyAsText()) shouldBe
                    error("api.unexpected_error", "Something went wrong, the request could not be answered.")
                failed.bodyAsText() shouldNotContain "secret"
            }

            // the malformed body is answered by ApiErrors, once
            val malformed =
                client.post("/api/names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":""")
                }
            malformed.status shouldBe HttpStatusCode.BadRequest
            Json.decodeFromString<ApiErrorResponse>(malformed.bodyAsText()) shouldBe error("api.malformed_request", "The request is malformed.")
        }

    @Test
    fun `answers a too large body with a 413 and the error body`() =
        test {
            for (path in listOf("/api/large", "/plain/large")) {
                val large = client.post(path)
                large.status shouldBe HttpStatusCode.PayloadTooLarge
                Json.decodeFromString<ApiErrorResponse>(large.bodyAsText()) shouldBe error("api.too_large_request", "The request body is too large.")
            }
        }

    @Test
    fun `the language is the first one of Accept-Language there is a catalog for, else english`() {
        Language.of("de-DE,de;q=0.9,en;q=0.8") shouldBe Language.DE
        Language.of("fr-FR,fr;q=0.9,de;q=0.5") shouldBe Language.DE
        Language.of("de;q=0,en") shouldBe Language.EN
        Language.of("fr") shouldBe Language.EN
        Language.of("*") shouldBe Language.EN
        Language.of("not a header ;;") shouldBe Language.EN
        Language.of(null) shouldBe Language.EN
    }

    @Test
    fun `a text without a german one is english, a text of a placeholder is in the same language`() {
        Text(TestTexts.ONLY_ENGLISH).render(Language.DE) shouldBe "Only in english."
        Text(TestTexts.BLANK, "field" to Text(ApiTexts.INVALID_OFFSET)).render(Language.DE) shouldBe
            "offset muss eine Zahl von mindestens 0 sein. darf nicht leer sein."
    }

    @Test
    fun `the catalog of the api has every text in every language`() {
        catalogProblems(ApiTexts) shouldBe emptyList()
    }
}

private object TestTexts : Catalog("apitest") {
    val BLANK = text("blank")
    val ONLY_ENGLISH = text("only_english")
}

private fun error(
    code: String,
    message: String,
) = ApiErrorResponse(listOf(ApiErrorMessage(code, message)))
