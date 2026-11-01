package io.pelle.sak.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun `tells what was checked and its state, with a message when there is one`() {
        val name = Text(ApiTexts.UNEXPECTED_ERROR)
        val message = Text(ApiTexts.MALFORMED_REQUEST)
        val healthy = Health { HealthStatus(name, HealthState.HEALTHY) }
        val unhealthy = Health { HealthStatus(name, HealthState.UNHEALTHY, message) }

        healthy.check() shouldBe HealthStatus(name, HealthState.HEALTHY, null)
        unhealthy.check().let {
            it.name shouldBe name
            it.state shouldBe HealthState.UNHEALTHY
            it.message shouldBe message
        }
    }
}
