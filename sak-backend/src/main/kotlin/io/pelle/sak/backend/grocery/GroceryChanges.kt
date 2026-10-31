package io.pelle.sak.backend.grocery

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// the ids of the lists that changed, as they change (LIST-4); the backend is one process, so every
// client that listens hears of each change here. A slow listener misses the oldest ids, never the
// newest: a client loads its list anew on any change
class GroceryChanges {
    private val changed = MutableSharedFlow<Int>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val lists: SharedFlow<Int> = changed.asSharedFlow()

    fun changed(listId: Int) {
        changed.tryEmit(listId)
    }

    private companion object {
        const val BUFFER = 256
    }
}
