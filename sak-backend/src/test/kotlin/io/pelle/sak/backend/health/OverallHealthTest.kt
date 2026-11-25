package io.pelle.sak.backend.health

import io.kotest.matchers.shouldBe
import io.pelle.sak.core.HealthState
import io.pelle.sak.core.HealthStatus
import io.pelle.sak.core.Text
import io.pelle.sak.core.TextKey
import org.junit.jupiter.api.Test

class OverallHealthTest {
    private fun status(state: HealthState) = HealthStatus(Text(TextKey("test", "a_check")), state)

    @Test
    fun `the system is unhealthy when a check is, else unknown when one could not tell, else healthy`() {
        overall(emptyList()) shouldBe HealthState.HEALTHY
        overall(listOf(status(HealthState.HEALTHY), status(HealthState.HEALTHY))) shouldBe HealthState.HEALTHY
        overall(listOf(status(HealthState.HEALTHY), status(HealthState.UNKNOWN))) shouldBe HealthState.UNKNOWN
        overall(listOf(status(HealthState.UNKNOWN), status(HealthState.UNHEALTHY))) shouldBe HealthState.UNHEALTHY
    }
}
