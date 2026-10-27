package io.pelle.sak.backend

import io.github.oshai.kotlinlogging.KotlinLogging
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
import io.pelle.sak.auth.RateLimiter
import io.pelle.sak.auth.SessionPrincipal
import io.pelle.sak.backend.labels.idParameter
import io.pelle.sak.model.files.DiscoverTextResult
import io.pelle.sak.model.files.FileService
import io.pelle.sak.model.files.FileTextService
import io.pelle.sak.model.files.StoreFileResult
import io.pelle.sak.model.files.StoredFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Duration

private val logger = KotlinLogging.logger {}

private const val FILES_TAG = "files"

// where the files of the file storage are uploaded and served, both with a session only
const val FILES_PATH = "$API_PATH_PREFIX$API_VERSION/files"

// url is where the file is served, thumbnailUrl where a small jpeg of the image is
@Serializable
data class FileResponse(
    val id: Int,
    val contentType: String,
    val size: Long,
    val url: String,
    val thumbnailUrl: String,
    val createdAt: String,
)

fun fileUrl(id: Int) = "$FILES_PATH/$id"

fun thumbnailUrl(id: Int) = "${fileUrl(id)}/thumbnail"

@Serializable
data class DiscoverTextResponse(
    val text: String,
)

// discovering text costs money at openrouter, so every user may only ask this often
fun discoverTextLimiter() = RateLimiter(30, Duration.ofHours(1))

fun StoredFile.response() = FileResponse(id, contentType, size, fileUrl(id), thumbnailUrl(id), createdAt.toString())

// uploads to the file storage and the discovery of the text in them, meant to be mounted behind
// the console's authentication with a body limit that lets the files through
@OptIn(ExperimentalKtorApi::class)
fun Route.files(
    fileService: FileService,
    fileTextService: FileTextService,
    discoverTextLimiter: RateLimiter = discoverTextLimiter(),
) {
    install(ContentNegotiation) { json() }
    install(ApiErrors)

    post {
        val uploadedBy = call.principal<SessionPrincipal>()!!.email
        val result = withContext(Dispatchers.IO) { call.receiveStream().use { fileService.store(it, uploadedBy) } }
        when (result) {
            is StoreFileResult.Stored -> call.respond(HttpStatusCode.Created, result.file.response())
            StoreFileResult.TooLarge ->
                call.respondErrors("A file must have at most ${fileService.maxSize / (1024 * 1024)} MB.", status = HttpStatusCode.PayloadTooLarge)
            StoreFileResult.UnsupportedType -> call.respondErrors("Only JPEG, PNG, GIF and WebP images and WAV recordings can be stored.")
        }
    }.describe {
        tag(FILES_TAG)
        summary = "Store a file"
        description =
            "The body is the content of the file. Every upload is a file of its own, served at $FILES_PATH/{id}. " +
            "The type is told by the content."
        responses {
            HttpStatusCode.Created {
                description = "The stored file"
                schema = jsonSchema<FileResponse>()
            }
            errors(HttpStatusCode.BadRequest, "A type the storage does not take")
            errors(HttpStatusCode.PayloadTooLarge, "A file larger than the storage takes")
        }
    }

    post("/{id}/discover/text") {
        if (!discoverTextLimiter.tryAcquire(call.principal<SessionPrincipal>()!!.email)) {
            call.respondErrors("Too many texts were discovered, please try again later.", status = HttpStatusCode.TooManyRequests)
            return@post
        }
        val id = call.idParameter() ?: return@post
        when (val result = withContext(Dispatchers.IO) { fileTextService.discover(id) }) {
            is DiscoverTextResult.Discovered -> call.respond(DiscoverTextResponse(result.text))
            DiscoverTextResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            DiscoverTextResult.Unsupported -> call.respondErrors("Text is only discovered in images and recordings.")
            DiscoverTextResult.NotConfigured ->
                call.respondErrors("Text discovery is not configured.", status = HttpStatusCode.ServiceUnavailable)
            is DiscoverTextResult.Failed -> {
                logger.warn(result.cause) { "could not discover the text in file $id" }
                call.respondErrors("The text could not be discovered, please try again later.", status = HttpStatusCode.BadGateway)
            }
        }
    }.describe {
        tag(FILES_TAG)
        summary = "Discover the text in an image or a recording"
        description =
            "Reads the text in an image with a vision model, writes down a recording with a speech to text model, both at " +
            "OpenRouter; providers that collect the data are not asked. The text is kept with the file, a file is only read once."
        parameters { fileId() }
        responses {
            HttpStatusCode.OK {
                description = "The text in the image or the recording, empty when there is none"
                schema = jsonSchema<DiscoverTextResponse>()
            }
            errors(HttpStatusCode.BadRequest, "The file is neither an image nor a recording")
            HttpStatusCode.NotFound { description = "There is no such file" }
            errors(HttpStatusCode.TooManyRequests, "The user discovered too many texts lately")
            errors(HttpStatusCode.BadGateway, "OpenRouter could not read the file")
            errors(HttpStatusCode.ServiceUnavailable, "No OpenRouter token is configured")
        }
    }
}

// only the browser of the signed in user keeps them, no shared cache
private const val IMMUTABLE = "private, max-age=31536000, immutable"

// the content of the stored files and of their thumbnails, which never change under the id; meant
// to be mounted behind the authentication like the uploads
@OptIn(ExperimentalKtorApi::class)
fun Route.servedFiles(fileService: FileService) {
    get("/{id}") {
        val id = call.idParameter() ?: return@get
        val file = withContext(Dispatchers.IO) { fileService.byId(id) }
        if (file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
        call.respond(LocalPathContent(fileService.path(file), ContentType.parse(file.contentType)))
    }.describe {
        tag(FILES_TAG)
        summary = "The content of a stored file"
        parameters { fileId() }
        responses {
            HttpStatusCode.OK { description = "The content with its type" }
            HttpStatusCode.NotFound { description = "There is no such file" }
        }
    }

    get("/{id}/thumbnail") {
        val id = call.idParameter() ?: return@get
        val thumbnail =
            withContext(Dispatchers.IO) {
                fileService.byId(id)?.let { fileService.thumbnail(it) }
            }
        if (thumbnail == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
        call.respond(LocalPathContent(thumbnail, ContentType.Image.JPEG))
    }.describe {
        tag(FILES_TAG)
        summary = "The thumbnail of a stored image or recording"
        description =
            "A JPEG of at most ${FileService.THUMBNAIL_SIZE} pixels on the longer side, of a recording its waveform; " +
            "made when it is first asked for."
        parameters { fileId() }
        responses {
            HttpStatusCode.OK { description = "The thumbnail" }
            HttpStatusCode.NotFound { description = "There is no such file, or it is neither an image nor a recording that can be read" }
        }
    }
}

private fun Parameters.Builder.fileId() =
    path("id") {
        description = "The id of the file"
        required = true
        schema = jsonSchema<Int>()
    }
