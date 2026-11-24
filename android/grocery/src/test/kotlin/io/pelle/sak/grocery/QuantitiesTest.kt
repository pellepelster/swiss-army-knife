package io.pelle.sak.grocery

import org.junit.Assert.assertEquals
import org.junit.Test

class QuantitiesTest {
    @Test
    fun `an amount takes a comma or a point, also without a leading zero`() {
        assertEquals(Amount.Of(9_999_999.999), amountOf("9999999,999"))
        assertEquals(Amount.Of(0.5), amountOf(".5"))
    }
}
