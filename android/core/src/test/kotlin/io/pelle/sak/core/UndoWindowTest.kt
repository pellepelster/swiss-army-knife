package io.pelle.sak.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UndoWindowTest {
    private val sent = mutableListOf<String>()

    private fun TestScope.window(millis: Long = 1_000) = UndoWindow<String>(this, { millis }) { value -> Job().also { sent += value } }

    @Test
    fun `a value is sent once its time ran out, not before`() =
        runTest {
            val window = window()
            assertEquals(1, window.start("milk"))
            advanceTimeBy(999)
            runCurrent()
            assertEquals(emptyList<String>(), sent)
            assertEquals("milk", window.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("milk"), sent)
            assertNull(window.value)
            advanceUntilIdle()
            assertEquals(listOf("milk"), sent)
        }

    @Test
    fun `undo answers the value, which is never sent`() =
        runTest {
            val window = window()
            window.start("milk")
            assertEquals("milk", window.undo())
            assertNull(window.undo())
            advanceUntilIdle()
            assertEquals(emptyList<String>(), sent)
            assertNull(window.committed)
        }

    @Test
    fun `a new start sends the value before at once, its own time starts again`() =
        runTest {
            val window = window()
            window.start("milk")
            advanceTimeBy(600)
            assertEquals(2, window.start("eggs"))
            assertEquals(listOf("milk"), sent)
            // the time of the first one does not end the second
            advanceTimeBy(600)
            runCurrent()
            assertEquals(listOf("milk"), sent)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(listOf("milk", "eggs"), sent)
        }

    @Test
    fun `commit sends at once and answers the request, drop sends nothing`() =
        runTest {
            val window = window()
            assertNull(window.commit())
            window.start("milk")
            val request = window.commit()
            assertEquals(listOf("milk"), sent)
            assertSame(request, window.committed)
            window.start("eggs")
            window.drop()
            advanceUntilIdle()
            assertEquals(listOf("milk"), sent)
            assertSame(request, window.committed)
        }

    @Test
    fun `update changes the value that waits, what is sent is the changed one`() =
        runTest {
            val window = window()
            window.update { "nothing" }
            assertNull(window.value)
            window.start("milk")
            window.update { "$it 2 l" }
            advanceUntilIdle()
            assertEquals(listOf("milk 2 l"), sent)
        }

    @Test
    fun `the time is read when a window starts`() =
        runTest {
            var millis = 1_000L
            val window = UndoWindow<String>(this, { millis }) { value -> Job().also { sent += value } }
            millis = 3_000
            window.start("milk")
            advanceTimeBy(2_999)
            runCurrent()
            assertEquals(emptyList<String>(), sent)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("milk"), sent)
        }
}
