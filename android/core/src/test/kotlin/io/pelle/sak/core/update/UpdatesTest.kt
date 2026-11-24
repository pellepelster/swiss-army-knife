package io.pelle.sak.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    @Test
    fun `only a newer released version of the deployment is an update`() {
        assertEquals("1.4.3", newer("1.4.2", "1.4.3"))
        assertEquals("1.10.0", newer("1.9.9", "1.10.0"))
        assertEquals("2.0.0", newer("1.4.2", "2.0.0"))
        assertNull(newer("1.4.2", "1.4.2"))
        // an older deployment: android does not install an older version over a newer one
        assertNull(newer("1.4.2", "1.4.1"))
        // a build of no release, a deployment of no release or of no version
        assertNull(newer("latest", "1.4.3"))
        assertNull(newer("1.4.2", "latest"))
        assertNull(newer("1.4.2", "1.4.3-rc"))
        assertNull(newer("1.4.2", null))
    }
}
