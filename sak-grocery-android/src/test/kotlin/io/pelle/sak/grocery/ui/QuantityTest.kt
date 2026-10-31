package io.pelle.sak.grocery.ui

import io.pelle.sak.grocery.api.GroceryItemResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantityTest {
    private fun item(
        amount: Double?,
        unit: String? = null,
    ) = GroceryItemResponse(1, 1, "Milk", amount, unit)

    @Test
    fun `an amount without trailing zeros, with its unit`() {
        assertEquals("1.5 l", quantity(item(1.5, "l")))
        assertEquals("2", quantity(item(2.0)))
        assertEquals("0.125 kg", quantity(item(0.125, "kg")))
        assertNull(quantity(item(null)))
    }
}
