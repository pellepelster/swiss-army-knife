package io.pelle.sak.backend.files

import io.pelle.sak.backend.ApiPath
import io.pelle.sak.api.API_BASE_PATH
import io.pelle.sak.core.logText
import io.pelle.sak.core.Text
import io.pelle.sak.api.apiTime
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
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
import io.pelle.sak.core.RateLimiter
import io.pelle.sak.auth.owner
import io.pelle.sak.api.API_PATH_PREFIX
import io.pelle.sak.api.API_VERSION
import io.pelle.sak.backend.files.model.DiscoverTextResponse
import io.pelle.sak.backend.files.model.FileResponse
import io.pelle.sak.api.idParameter
import io.pelle.sak.model.files.StoredFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

private val logger = KotlinLogging.logger {}

private const val FILES_TAG = "files"

// where the files of the file storage are uploaded and served, both with a session only
const val FILES_PATH = "$API_BASE_PATH${ApiPath.FILES}"

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
    // the limit of what goes to openrouter, one for the notes and the grocery lists: the apps make it
    discoverTextLimiter: RateLimiter,
) {
    post {
        val result = withContext(Dispatchers.IO) { call.receiveStream().use { fileService.store(it, call.owner) } }
        when (result) {
            is FileStoreResult.Stored -> call.respond(HttpStatusCode.Created, result.file.response())
            FileStoreResult.TooLarge ->
                call.respondErrors(Text(FilesTexts.TOO_LARGE, "max" to fileService.maxSize / (1024 * 1024)), status = HttpStatusCode.PayloadTooLarge)
            FileStoreResult.NoRoom -> call.respondErrors(FilesTexts.NO_ROOM, status = HttpStatusCode.InsufficientStorage)
            is FileStoreResult.Invalid -> call.respondErrors(result.errors)
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
            errors(HttpStatusCode.InsufficientStorage, "The data volume has no room for more files")
        }
    }

    post("/{id}/discover/text") {
        val id = call.idParameter() ?: return@post
        val owner = call.owner
        // only a request that goes to OpenRouter takes from the limit
        when (val result = withContext(Dispatchers.IO) { fileTextService.discover(owner, id) { discoverTextLimiter.tryAcquire(owner) } }) {
            is FileTextDiscoverResult.Discovered -> call.respond(DiscoverTextResponse(result.text))
            FileTextDiscoverResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            FileTextDiscoverResult.Unsupported -> call.respondErrors(FilesTexts.NOT_DISCOVERABLE)
            FileTextDiscoverResult.NotConfigured ->
                call.respondErrors(FilesTexts.NO_API_KEY, status = HttpStatusCode.Conflict)
            FileTextDiscoverResult.Disabled ->
                call.respondErrors(FilesTexts.AI_OFF, status = HttpStatusCode.Conflict)
            FileTextDiscoverResult.Limited ->
                call.respondErrors(FilesTexts.TOO_MANY_DISCOVERIES, status = HttpStatusCode.TooManyRequests)
            is FileTextDiscoverResult.Failed -> {
                // not the causes: a json error names what the model answered, the text of the user
                logger.warn { "could not discover the text in file $id: ${logText(result.cause.message ?: result.cause.javaClass.name)}" }
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
            errors(HttpStatusCode.NotFound, "The signed in user has no such file")
            errors(HttpStatusCode.Conflict, "The AI features are turned off in the settings, or no OpenRouter API key is set there")
            errors(HttpStatusCode.TooManyRequests, "The user had too many texts discovered at OpenRouter lately; a known text does not count")
            errors(HttpStatusCode.BadGateway, "OpenRouter could not read the file")
        }
    }
}

// only the browser of the signed in user keeps them, no shared cache
internal const val IMMUTABLE = "private, max-age=31536000, immutable"

// the content of the stored files and of their thumbnails, which never change under the id, only
// to the user that uploaded them; meant to be mounted behind the authentication like the uploads
@OptIn(ExperimentalKtorApi::class)
fun Route.servedFiles(fileService: FileService) {
    get("/{id}") {
        val id = call.idParameter() ?: return@get
        val content =
            withContext(Dispatchers.IO) {
                fileService.byId(call.owner, id)?.let { file -> fileService.content(file)?.let { BlobResponse(it, ContentType.parse(file.contentType)) } }
            }
        if (content == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
        call.respond(content)
    }.describe {
        tag(FILES_TAG)
        summary = "The content of a stored file"
        parameters { fileId() }
        responses {
            HttpStatusCode.OK { description = "The content with its type" }
            errors(HttpStatusCode.NotFound, "The signed in user has no such file")
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
        call.respond(BlobResponse(thumbnail, ContentType.Image.JPEG))
    }.describe {
        tag(FILES_TAG)
        summary = "The thumbnail of a stored image or recording"
        description =
            "A JPEG of at most ${FileService.THUMBNAIL_SIZE} pixels on the longer side, of a recording its waveform; " +
            "made when it is first asked for."
        parameters { fileId() }
        responses {
            HttpStatusCode.OK { description = "The thumbnail" }
            errors(
                HttpStatusCode.NotFound,
                "The signed in user has no such file, or it is neither an image nor a recording that can be read",
            )
        }
    }
}

private fun Parameters.Builder.fileId() =
    path("id") {
        description = "The id of the file"
        required = true
        schema = jsonSchema<Int>()
    }
