package io.pelle.sak.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class ApiTimeTest {
    @Test
    fun `a time is ISO 8601 in UTC with milliseconds`() {
        Instant.parse("2026-10-29T08:00:00Z").apiTime() shouldBe "2026-10-29T08:00:00.000Z"
        Instant.parse("2026-10-29T08:00:00.123456789Z").apiTime() shouldBe "2026-10-29T08:00:00.123Z"
        Instant.parse("2026-10-29T10:00:00+02:00").apiTime() shouldBe "2026-10-29T08:00:00.000Z"
        // it reads back to the same instant, cut to milliseconds
        Instant.parse(Instant.parse("2026-10-29T08:00:00.5Z").apiTime()) shouldBe Instant.parse("2026-10-29T08:00:00.500Z")
    }
}
