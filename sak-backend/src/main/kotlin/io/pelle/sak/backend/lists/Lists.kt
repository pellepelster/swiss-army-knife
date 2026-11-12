package io.pelle.sak.backend.lists

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.utils.io.writeStringUtf8
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.api.TextKey
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.isOneEmoji
import io.pelle.sak.model.lists.ListUpdate
import io.pelle.sak.model.lists.SakList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

// what every list-based app (LIST-1) does with its lists the same way: the values of a list checked,
// the answers to creating and changing one, and the stream of the changes (LIST-4)

// how long the name of a list is at most
const val MAX_LIST_NAME_LENGTH = 64

// how often a comment keeps the stream of the changes open
private val KEEP_ALIVE = 25.seconds

private const val CHANGES_BUFFER = 256

// the texts of an app about its lists, each a text of the app's own catalog
data class ListTexts(
    val invalidIcon: TextKey,
    val invalidListName: TextKey,
    val listExists: TextKey,
    val onlyOwnerShares: TextKey,
    val ownerListExists: TextKey,
)

sealed interface ListResult {
    data class Saved(
        val list: SakList,
    ) : ListResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : ListResult

    data object NotFound : ListResult

    data object Exists : ListResult

    // only the owner shares or unshares a list (LIST-2)
    data object NotOwner : ListResult

    // another user renames a shared list to a name the owner has for another list
    data object OwnerExists : ListResult
}

// the name and the icon of a list as they are stored: trimmed, an empty icon none
data class ListValues(
    val name: String,
    val icon: String?,
)

// the name trimmed and the icon, one emoji, empty or none for no icon; the errors when they are not
// valid
fun listValues(
    name: String,
    icon: String?,
    texts: ListTexts,
): Pair<ListValues, List<ErrorMessage>> {
    val values = ListValues(name.trim(), icon?.trim()?.ifEmpty { null })
    val errors =
        listOfNotNull(
            if (values.name.isEmpty() || values.name.length > MAX_LIST_NAME_LENGTH || values.name.any { it.isISOControl() }) {
                ErrorMessage(texts.invalidListName, "max" to MAX_LIST_NAME_LENGTH)
            } else {
                null
            },
            values.icon?.takeUnless(::isOneEmoji)?.let { ErrorMessage(texts.invalidIcon) },
        )
    return values to errors
}

// what changing a list came to, as the answer of the service
fun ListUpdate.result(): ListResult =
    when (this) {
        is ListUpdate.Updated -> ListResult.Saved(list)
        ListUpdate.NotFound -> ListResult.NotFound
        ListUpdate.NameTaken -> ListResult.Exists
        ListUpdate.NotOwner -> ListResult.NotOwner
        ListUpdate.OwnerNameTaken -> ListResult.OwnerExists
    }

// the list as response answers it, 201 for a new one
suspend fun ApplicationCall.respondList(
    result: ListResult,
    texts: ListTexts,
    created: Boolean = false,
    response: (SakList, String) -> Any,
) = when (result) {
    is ListResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, response(result.list, owner))
    is ListResult.Invalid -> respondErrors(result.errors)
    ListResult.NotFound -> respond(HttpStatusCode.NotFound)
    ListResult.Exists -> respondErrors(texts.listExists, status = HttpStatusCode.Conflict)
    ListResult.NotOwner -> respondErrors(texts.onlyOwnerShares, status = HttpStatusCode.Forbidden)
    ListResult.OwnerExists -> respondErrors(texts.ownerListExists, status = HttpStatusCode.Conflict)
}

// the ids of the lists of an app that changed, as they change (LIST-4); the backend is one process,
// so every client that listens hears of each change here. A slow listener misses the oldest ids,
// never the newest: a client loads its list anew on any change
class ListChanges {
    // a change and whether every user hears of it, also one who does not see the list any more
    private data class Change(
        val listId: Int,
        val toAll: Boolean,
    )

    private val changed = MutableSharedFlow<Change>(extraBufferCapacity = CHANGES_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // a change of a list; toAll when the users who saw it until now do not see it any more, the owner
    // unshared it: their clients load their lists anew, without it
    fun changed(
        listId: Int,
        toAll: Boolean = false,
    ) {
        changed.tryEmit(Change(listId, toAll))
    }

    // the ids of the lists the user sees that change from now on, a list deleted included, and a list
    // unshared that the user saw until then (LIST-4); sees tells whether the user sees a list or saw it
    // until it was deleted
    fun of(
        user: String,
        sees: (String, Int) -> Boolean,
    ): Flow<Int> = changed.filter { it.toAll || withContext(Dispatchers.IO) { sees(user, it.listId) } }.map { it.listId }

    // a change of a list as it was saved; an unshared one reaches every user who saw it (LIST-4)
    fun changed(update: ListUpdate) {
        if (update is ListUpdate.Updated) changed(update.list.id, toAll = update.unshared)
    }
}

// GET /changes: server-sent events, one for each change of a list the user sees (LIST-4), what changes
// gives for the user; a comment keeps the connection open while nothing changes, proxies and phones
// close a quiet one. items names what is on the lists, e.g. "items" or "tasks"
@OptIn(ExperimentalKtorApi::class)
fun Route.listChanges(
    tag: String,
    app: String,
    items: String,
    changes: (String) -> Flow<Int>,
) {
    get("/changes") {
        val listChanges = changes(call.owner)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytesWriter(ContentType.Text.EventStream) {
            // the headers go out at once, the client knows it listens
            writeStringUtf8(": listening\n\n")
            flush()
            val keepAlive =
                flow {
                    while (true) {
                        delay(KEEP_ALIVE)
                        emit(": keep-alive\n\n")
                    }
                }
            merge(listChanges.map { "event: changed\ndata: {\"listId\":$it}\n\n" }, keepAlive).collect {
                writeStringUtf8(it)
                flush()
            }
        }
    }.describe {
        tag(tag)
        summary = "The changes of the $app lists"
        description =
            "A stream of server-sent events (text/event-stream) that stays open: an event changed with the id of the " +
            "list ({\"listId\": 2}) whenever a list the user sees, or its $items, change, also when it is deleted, and when its " +
            "owner unshares it, to every user who saw it until then. The " +
            "event carries no data of the list, the client loads it again. A comment every " +
            "${KEEP_ALIVE.inWholeSeconds} seconds keeps the connection open."
        responses {
            HttpStatusCode.OK { description = "The stream of the changes" }
        }
    }
}
