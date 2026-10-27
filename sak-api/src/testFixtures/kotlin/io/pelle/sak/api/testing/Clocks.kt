package io.pelle.sak.api.testing

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// the moment the tests take as now
val TEST_NOW: Instant = Instant.parse("2026-10-27T08:00:00Z")

// a clock standing still, at TEST_NOW unless another moment is given
fun testClock(now: Instant = TEST_NOW): Clock = Clock.fixed(now, ZoneOffset.UTC)
