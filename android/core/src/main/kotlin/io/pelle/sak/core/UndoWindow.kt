package io.pelle.sak.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// a change that waits for its undo (UX-4.2), e.g. a check: committed once millis ran out, when the next
// one starts, when the app goes to the background and on the sign-out; dropped unsent when the session
// ends (SakModel.undoWindow). send sends the value and answers the job of its request, which a load
// waits for (committed)
class UndoWindow<T : Any>(
    private val scope: CoroutineScope,
    // the time of the undo, read as the timer of a window starts
    private val millis: () -> Long,
    private val send: (T) -> Job,
) {
    private var timer: Job? = null

    // the value that waits, null without one
    var value: T? = null
        private set

    // counts the windows started: the bar of the time left restarts for each one (Undo.serial)
    var serial = 0
        private set

    // the request of the value committed last, null before the first one
    var committed: Job? = null
        private set

    // the value waits for its undo; one that waits before is committed first. Answers the serial of
    // this window
    fun start(value: T): Int {
        commit()
        this.value = value
        timer =
            scope.launch {
                delay(millis())
                commit()
            }
        return ++serial
    }

    // the value that waited, which is not sent; null without one
    fun undo(): T? = take()

    // the value that waits is sent now; the job of its request, null without one
    fun commit(): Job? = take()?.let(send)?.also { committed = it }

    // the value that waits goes unsent, e.g. the session ended
    fun drop() {
        take()
    }

    // the value that waits changed, e.g. renamed during the window; it is sent so
    fun update(change: (T) -> T) {
        value = value?.let(change)
    }

    private fun take(): T? {
        val taken = value ?: return null
        value = null
        timer?.cancel()
        timer = null
        return taken
    }
}
