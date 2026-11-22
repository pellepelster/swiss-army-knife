package io.pelle.sak.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun `tells what was checked and its state, with a message when there is one`() {
        val name = Text(TextKey("api", "unexpected_error"))
        val message = Text(TextKey("api", "malformed_request"))
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
