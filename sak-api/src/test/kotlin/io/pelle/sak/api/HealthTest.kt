package io.pelle.sak.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun `tells what was checked and its state, with a message when there is one`() {
        val healthy = Health { HealthStatus("Database", HealthState.HEALTHY) }
        val unhealthy = Health { HealthStatus("Caddy", HealthState.UNHEALTHY, "caddy did not answer") }

        healthy.check() shouldBe HealthStatus("Database", HealthState.HEALTHY, null)
        unhealthy.check().let {
            it.name shouldBe "Caddy"
            it.state shouldBe HealthState.UNHEALTHY
            it.message shouldBe "caddy did not answer"
        }
    }
}
