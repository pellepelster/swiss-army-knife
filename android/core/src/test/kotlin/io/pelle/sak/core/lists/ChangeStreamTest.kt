package io.pelle.sak.core.lists

import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.lists.ChangeStream.Companion.MAX_RETRY_DELAY_MS
import io.pelle.sak.core.lists.ChangeStream.Companion.RETRY_DELAY_MS
import io.pelle.sak.core.lists.ChangeStream.Companion.STABLE_STREAM
import io.pelle.sak.core.session.Stored
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// the stream of the changes on the virtual time of the test: each opening runs the next of the
// streams the test gives, the last one again when they run out
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeStreamTest {
    // when each stream was asked for, on the virtual time
    private val asked = mutableListOf<Long>()
    private val reconnects = mutableListOf<Long>()
    private val changed = mutableListOf<Int>()
    private val ended = mutableListOf<String?>()
    private var stored: Stored? = Stored(apiUrl = "https://api.example.org/v1", token = "t")

    private fun TestScope.stream(vararg streams: suspend FlowCollector<Int>.(opened: suspend () -> Unit) -> Unit): ChangeStream {
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId) = this

                override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
            }
        return ChangeStream(
            backgroundScope,
            clock,
            session = { stored },
            changes = { _, opened ->
                flow {
                    val next = streams[asked.size.coerceAtMost(streams.size - 1)]
                    asked += testScheduler.currentTime
                    next(opened)
                }
            },
            ended = { _, token -> ended += token },
            onReconnect = { reconnects += testScheduler.currentTime },
            onChange = { changed += it },
        )
    }

    // a stream that can not be opened, the connection fails
    private val unreachable: suspend FlowCollector<Int>.(suspend () -> Unit) -> Unit = { throw IOException("unreachable") }

    // a stream that opens, stays open for millis and ends
    private fun open(
        millis: Long,
        vararg changes: Int,
    ): suspend FlowCollector<Int>.(suspend () -> Unit) -> Unit =
        { opened ->
            opened()
            changes.forEach { emit(it) }
            delay(millis)
            throw IOException("ended")
        }

    @Test
    fun `the wait before the next stream doubles up to MAX_RETRY_DELAY_MS`() =
        runTest {
            stream(unreachable).start()
            advanceTimeBy(200_000)
            runCurrent()
            val waits = asked.zipWithNext { a, b -> b - a }
            assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, MAX_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS), waits.take(7))
            assertEquals(MAX_RETRY_DELAY_MS, waits.last())
        }

    @Test
    fun `the wait is short again only after a stream stayed open STABLE_STREAM`() =
        runTest {
            val stable = STABLE_STREAM.toMillis()
            // two failures, then a stream open a little shorter than stable: the wait goes on growing
            val short = stream(unreachable, unreachable, open(stable - 1), unreachable).also { it.start() }
            advanceTimeBy(100_000)
            runCurrent()
            // asked at 0, 1 s, 3 s; open until 3 s + stable - 1 ms, then 4 s of wait
            assertEquals(listOf(0L, 1_000, 3_000, 3_000 + stable - 1 + 4_000), asked.take(4))

            short.stop()
            asked.clear()
            stream(unreachable, unreachable, open(stable), unreachable).start()
            val start = testScheduler.currentTime
            advanceTimeBy(100_000)
            runCurrent()
            // open stable: the next wait is RETRY_DELAY_MS
            assertEquals(listOf(0L, 1_000, 3_000, 3_000 + stable + RETRY_DELAY_MS), asked.take(4).map { it - start })
        }

    @Test
    fun `a stream open again after an open one says it reconnected, not the first one, not after a restart`() =
        runTest {
            val stream = stream(open(10, 7), unreachable, open(10, 8), open(10))
            stream.start()
            runCurrent()
            assertEquals(emptyList<Long>(), reconnects)
            assertEquals(listOf(7), changed)

            // the second stream fails before it opens, the third opens: missed changes
            advanceTimeBy(10 + 1_000 + 2_000)
            runCurrent()
            assertEquals(3, asked.size)
            assertEquals(listOf(asked[2]), reconnects)
            assertEquals(listOf(7, 8), changed)

            // a new session: the next stream is a first one again, and it opens at once
            stream.restart()
            runCurrent()
            assertEquals(4, asked.size)
            assertEquals(1, reconnects.size)
        }

    @Test
    fun `restart opens a new stream only when it listened, stop ends it, no session ends the listening`() =
        runTest {
            val stream = stream(open(60_000))
            stream.restart()
            runCurrent()
            assertEquals(emptyList<Long>(), asked)

            stream.start()
            // a second start while it listens changes nothing
            stream.start()
            runCurrent()
            assertEquals(1, asked.size)
            stream.stop()
            advanceTimeBy(200_000)
            runCurrent()
            assertEquals(1, asked.size)

            stored = null
            stream.start()
            advanceTimeBy(200_000)
            runCurrent()
            assertEquals(1, asked.size)
        }

    @Test
    fun `a 401 ends the session with the token of the stream, other refusals only wait`() =
        runTest {
            stream(
                { throw ApiException(500, UiText.Plain("down")) },
                { throw ApiException(401, UiText.Plain("ended")) },
                open(60_000),
            ).start()
            runCurrent()
            assertEquals(emptyList<String?>(), ended)
            advanceTimeBy(RETRY_DELAY_MS)
            runCurrent()
            assertEquals(listOf<String?>("t"), ended)
        }
}
