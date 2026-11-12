package io.pelle.sak.core.lists

import kotlinx.coroutines.flow.Flow

// the calls of the lists every list-based app has, on top of SakApi; L is the list as the backend of
// the app answers it (a generated response, e.g. TodoListResponse). Each app sends its own request
// and its own texts, the body of each call stays in its api
interface ListsApi<L> {
    // the lists the user sees, in the order the backend gives them: by name
    suspend fun lists(): List<L>

    // the new list of the signed in user
    suspend fun createList(
        name: String,
        shared: Boolean,
        icon: String?,
    ): L

    // the settings of a list the user sees; only the owner changes shared (LIST-2)
    suspend fun updateList(
        id: Int,
        name: String,
        shared: Boolean,
        icon: String?,
    ): L

    // a list of the user's own with its entries, the last one too (LIST-1)
    suspend fun deleteList(id: Int)

    // the ids of the lists the user sees as they change (LIST-4, SakApi.listChanges)
    fun changes(opened: suspend () -> Unit): Flow<Int>
}
