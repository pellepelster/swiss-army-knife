package io.pelle.sak.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun `tells the state of a check, with a message when there is one`() {
        val healthy = Health { HealthStatus(HealthState.HEALTHY) }
        val unhealthy = Health { HealthStatus(HealthState.UNHEALTHY, "caddy did not answer") }

        healthy.check() shouldBe HealthStatus(HealthState.HEALTHY, null)
        unhealthy.check().let {
            it.state shouldBe HealthState.UNHEALTHY
            it.message shouldBe "caddy did not answer"
        }
    }
}
