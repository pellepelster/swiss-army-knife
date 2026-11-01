package io.pelle.sak.backend.files

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.Text
import io.pelle.sak.api.apiTime
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.api.installApi
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.server.http.content.LocalPathContent
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.errors
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.RateLimiter
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.API_PATH_PREFIX
import io.pelle.sak.backend.API_VERSION
import io.pelle.sak.backend.files.model.DiscoverTextResponse
import io.pelle.sak.backend.files.model.FileResponse
import io.pelle.sak.api.idParameter
import io.pelle.sak.backend.files.DiscoverTextResult
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.FileTextService
import io.pelle.sak.backend.files.StoreFileResult
import io.pelle.sak.model.files.StoredFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

private val logger = KotlinLogging.logger {}

private const val FILES_TAG = "files"

// where the files of the file storage are uploaded and served, both with a session only
const val FILES_PATH = "$API_PATH_PREFIX$API_VERSION/files"

fun fileUrl(id: Int) = "$FILES_PATH/$id"

fun thumbnailUrl(id: Int) = "${fileUrl(id)}/thumbnail"

// discovering text costs money at openrouter, so every user may only ask this often
fun discoverTextLimiter() = RateLimiter(30, Duration.ofHours(1))

fun StoredFile.response() = FileResponse(id, contentType, size, fileUrl(id), thumbnailUrl(id), createdAt.apiTime())

// uploads to the file storage and the discovery of the text in them, meant to be mounted behind
// the authentication with a body limit that lets the files through
@OptIn(ExperimentalKtorApi::class)
fun Route.files(
    fileService: FileService,
    fileTextService: FileTextService,
    discoverTextLimiter: RateLimiter = discoverTextLimiter(),
) {
    installApi()

    post {
        val result = withContext(Dispatchers.IO) { call.receiveStream().use { fileService.store(it, call.owner) } }
        when (result) {
            is StoreFileResult.Stored -> call.respond(HttpStatusCode.Created, result.file.response())
            StoreFileResult.TooLarge ->
                call.respondErrors(Text(FilesTexts.TOO_LARGE, "max" to fileService.maxSize / (1024 * 1024)), status = HttpStatusCode.PayloadTooLarge)
            StoreFileResult.UnsupportedType -> call.respondErrors(FilesTexts.UNSUPPORTED_TYPE)
            StoreFileResult.ImageTooLarge ->
                call.respondErrors(Text(FilesTexts.IMAGE_TOO_LARGE, "max" to FileService.MAX_IMAGE_SIZE))
            StoreFileResult.RecordingTooLong ->
                call.respondErrors(Text(FilesTexts.RECORDING_TOO_LONG, "max" to FileService.MAX_RECORDING_SECONDS / 60))
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
            errors(
                HttpStatusCode.BadRequest,
                "A type the storage does not take, content that is no such file, an image larger than " +
                    "${FileService.MAX_IMAGE_SIZE} pixels, or a recording longer than " +
                    "${FileService.MAX_RECORDING_SECONDS / 60} minutes",
            )
            errors(HttpStatusCode.PayloadTooLarge, "A file larger than the storage takes")
        }
    }

    post("/{id}/discover/text") {
        val id = call.idParameter() ?: return@post
        val owner = call.owner
        // only a request that goes to OpenRouter takes from the limit
        when (val result = withContext(Dispatchers.IO) { fileTextService.discover(owner, id) { discoverTextLimiter.tryAcquire(owner) } }) {
            is DiscoverTextResult.Discovered -> call.respond(DiscoverTextResponse(result.text))
            DiscoverTextResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            DiscoverTextResult.Unsupported -> call.respondErrors(FilesTexts.NOT_DISCOVERABLE)
            DiscoverTextResult.NotConfigured ->
                call.respondErrors(FilesTexts.NO_API_KEY, status = HttpStatusCode.ServiceUnavailable)
            DiscoverTextResult.Disabled ->
                call.respondErrors(FilesTexts.AI_OFF, status = HttpStatusCode.Conflict)
            DiscoverTextResult.Limited ->
                call.respondErrors(FilesTexts.TOO_MANY_DISCOVERIES, status = HttpStatusCode.TooManyRequests)
            is DiscoverTextResult.Failed -> {
                logger.warn(result.cause) { "could not discover the text in file $id" }
                call.respondErrors(FilesTexts.DISCOVERY_FAILED, status = HttpStatusCode.BadGateway)
            }
        }
    }.describe {
        tag(FILES_TAG)
        summary = "Discover the text in an image or a recording"
        description =
            "Reads the text in an image with a vision model, writes down a recording with a speech to text model, both at " +
            "OpenRouter; providers that collect the data are not asked. The text is kept with the file, a file is only read once. " +
            "Needs the AI features turned on in the settings."
        parameters { fileId() }
        responses {
            HttpStatusCode.OK {
                description = "The text in the image or the recording, empty when there is none"
                schema = jsonSchema<DiscoverTextResponse>()
            }
            errors(HttpStatusCode.BadRequest, "The file is neither an image nor a recording")
            HttpStatusCode.NotFound { description = "The signed in user has no such file" }
            errors(HttpStatusCode.Conflict, "The AI features are turned off in the settings")
            errors(HttpStatusCode.TooManyRequests, "The user had too many texts discovered at OpenRouter lately; a known text does not count")
            errors(HttpStatusCode.BadGateway, "OpenRouter could not read the file")
            errors(HttpStatusCode.ServiceUnavailable, "No OpenRouter API key is set in the settings")
        }
    }
}

// only the browser of the signed in user keeps them, no shared cache
private const val IMMUTABLE = "private, max-age=31536000, immutable"

// the content of the stored files and of their thumbnails, which never change under the id, only
// to the user that uploaded them; meant to be mounted behind the authentication like the uploads
@OptIn(ExperimentalKtorApi::class)
fun Route.servedFiles(fileService: FileService) {
    get("/{id}") {
        val id = call.idParameter() ?: return@get
        val file = withContext(Dispatchers.IO) { fileService.byId(call.owner, id) }
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
            HttpStatusCode.NotFound { description = "The signed in user has no such file" }
        }
    }

    get("/{id}/thumbnail") {
        val id = call.idParameter() ?: return@get
        val thumbnail =
            withContext(Dispatchers.IO) {
                fileService.byId(call.owner, id)?.let { fileService.thumbnail(it) }
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
            HttpStatusCode.NotFound {
                description = "The signed in user has no such file, or it is neither an image nor a recording that can be read"
            }
        }
    }
}

private fun Parameters.Builder.fileId() =
    path("id") {
        description = "The id of the file"
        required = true
        schema = jsonSchema<Int>()
    }

// the texts of the files (backend.files in i18n/texts.yml)
object FilesTexts : Catalog("files") {
    val TOO_LARGE = text("too_large")
    val UNSUPPORTED_TYPE = text("unsupported_type")
    val IMAGE_TOO_LARGE = text("image_too_large")
    val RECORDING_TOO_LONG = text("recording_too_long")
    val TOO_MANY_DISCOVERIES = text("too_many_discoveries")
    val NOT_DISCOVERABLE = text("not_discoverable")
    val NO_API_KEY = text("no_api_key")
    val AI_OFF = text("ai_off")
    val DISCOVERY_FAILED = text("discovery_failed")
}
