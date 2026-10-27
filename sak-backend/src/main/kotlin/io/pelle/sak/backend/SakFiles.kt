package io.pelle.sak.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.server.http.content.LocalPathContent
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.ApiErrors
import io.pelle.sak.api.errors
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.SessionPrincipal
import io.pelle.sak.model.files.FileService
import io.pelle.sak.model.files.StoreFileResult
import io.pelle.sak.model.files.StoredFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private const val FILES_TAG = "files"

// where the files of the file storage are uploaded and served, served without a session: the
// sha-256 of a file is only known to whoever has its content or was given the url
const val FILES_PATH = "$API_PATH_PREFIX$API_VERSION/files"

// url is where the file is served, thumbnailUrl where a small jpeg of the image is
@Serializable
data class FileResponse(
    val sha256: String,
    val contentType: String,
    val size: Long,
    val url: String,
    val thumbnailUrl: String,
    val createdAt: String,
)

fun fileUrl(sha256: String) = "$FILES_PATH/$sha256"

fun thumbnailUrl(sha256: String) = "${fileUrl(sha256)}/thumbnail"

fun StoredFile.response() = FileResponse(sha256, contentType, size, fileUrl(sha256), thumbnailUrl(sha256), createdAt.toString())

// uploads to the file storage, meant to be mounted behind the console's authentication with a
// body limit that lets the files through
@OptIn(ExperimentalKtorApi::class)
fun Route.files(fileService: FileService) {
    install(ContentNegotiation) { json() }
    install(ApiErrors)

    post {
        val uploadedBy = call.principal<SessionPrincipal>()!!.email
        val result = withContext(Dispatchers.IO) { call.receiveStream().use { fileService.store(it, uploadedBy) } }
        when (result) {
            is StoreFileResult.Stored -> call.respond(HttpStatusCode.Created, result.file.response())
            StoreFileResult.TooLarge ->
                call.respondErrors("A file must have at most ${fileService.maxSize / (1024 * 1024)} MB.", status = HttpStatusCode.PayloadTooLarge)
            StoreFileResult.UnsupportedType -> call.respondErrors("Only JPEG, PNG, GIF and WebP images can be stored.")
        }
    }.describe {
        tag(FILES_TAG)
        summary = "Store a file"
        description =
            "The body is the content of the file. It is stored under its sha-256 and served at $FILES_PATH/{sha256} " +
            "to everyone that knows the hash; the same content is stored once. The type is told by the content."
        responses {
            HttpStatusCode.Created {
                description = "The stored file"
                schema = jsonSchema<FileResponse>()
            }
            errors(HttpStatusCode.BadRequest, "A type the storage does not take")
            errors(HttpStatusCode.PayloadTooLarge, "A file larger than the storage takes")
        }
    }
}

private const val IMMUTABLE = "public, max-age=31536000, immutable"

// the content of the stored files and of their thumbnails, which never change under the hash;
// served without a session
@OptIn(ExperimentalKtorApi::class)
fun Route.servedFiles(fileService: FileService) {
    get("/{sha256}") {
        val file = withContext(Dispatchers.IO) { fileService.bySha256(call.parameters["sha256"].orEmpty()) }
        if (file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
        call.respond(LocalPathContent(fileService.path(file), ContentType.parse(file.contentType)))
    }.describe {
        tag(FILES_TAG)
        summary = "The content of a stored file"
        parameters { sha256() }
        responses {
            HttpStatusCode.OK { description = "The content with its type" }
            HttpStatusCode.NotFound { description = "There is no such file" }
        }
    }

    get("/{sha256}/thumbnail") {
        val thumbnail =
            withContext(Dispatchers.IO) {
                fileService.bySha256(call.parameters["sha256"].orEmpty())?.let { fileService.thumbnail(it) }
            }
        if (thumbnail == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
        call.respond(LocalPathContent(thumbnail, ContentType.Image.JPEG))
    }.describe {
        tag(FILES_TAG)
        summary = "The thumbnail of a stored image"
        description = "A JPEG of at most ${FileService.THUMBNAIL_SIZE} pixels on the longer side, made when it is first asked for."
        parameters { sha256() }
        responses {
            HttpStatusCode.OK { description = "The thumbnail" }
            HttpStatusCode.NotFound { description = "There is no such file, or it is no image that can be read" }
        }
    }
}

private fun Parameters.Builder.sha256() =
    path("sha256") {
        description = "The sha-256 of the content, lower case hex"
        required = true
        schema = jsonSchema<String>()
    }
