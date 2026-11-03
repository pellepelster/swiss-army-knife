package io.pelle.sak.notes

import android.content.Intent.ACTION_SEND
import android.content.Intent.ACTION_SEND_MULTIPLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTest {
    @Test
    fun `a share is the photos of SEND or SEND_MULTIPLE with its text`() {
        assertEquals(Shared(listOf("a"), "caption"), shared(ACTION_SEND, "image/jpeg", listOf("a"), "caption"))
        assertEquals(Shared(listOf("a", "b"), ""), shared(ACTION_SEND_MULTIPLE, "image/*", listOf("a", null, "b"), null))
    }

    @Test
    fun `no share without a photo, of another type or another action`() {
        assertNull(shared(ACTION_SEND, "image/jpeg", listOf<String?>(null), "caption"))
        assertNull(shared(ACTION_SEND, "text/plain", listOf("a"), "caption"))
        assertNull(shared(ACTION_SEND, null, listOf("a"), null))
        assertNull(shared("android.intent.action.VIEW", "image/jpeg", listOf("a"), null))
    }
}
