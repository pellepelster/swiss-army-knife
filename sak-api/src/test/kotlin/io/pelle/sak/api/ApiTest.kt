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
import io.pelle.sak.api.model.ErrorResponse
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.model.PaginationResponse

class ApiTest {
    @Serializable
    data class Request(
        val name: String,
    )

    private fun test(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                routing {
                    route("/api") {
                        install(ContentNegotiation) { json() }
                        install(ApiErrors)
                        get("/numbers") {
                            val page = call.pageRequest() ?: return@get
                            call.respond(page.of((1..120).toList()))
                        }
                        post("/names") {
                            val request = call.receive<Request>()
                            if (request.name.isBlank()) call.respondErrors("name must not be blank.") else call.respond(request)
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
            Json.decodeFromString<ErrorResponse>(invalid.bodyAsText()).errors.size shouldBe 2
            client.get("/api/numbers?limit=x").status shouldBe HttpStatusCode.BadRequest

            val blank =
                client.post("/api/names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":" "}""")
                }
            blank.status shouldBe HttpStatusCode.BadRequest
            Json.decodeFromString<ErrorResponse>(blank.bodyAsText()) shouldBe ErrorResponse("name must not be blank.")

            val malformed =
                client.post("/api/names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"nam""")
                }
            malformed.status shouldBe HttpStatusCode.BadRequest
            Json.decodeFromString<ErrorResponse>(malformed.bodyAsText()) shouldBe ErrorResponse(MALFORMED_REQUEST)
        }
}
