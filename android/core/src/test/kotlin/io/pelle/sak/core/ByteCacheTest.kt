package io.pelle.sak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ByteCacheTest {
    @Test
    fun `the cache has at most its bytes, the ones used last stay`() {
        val cache = ByteCache(10)
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        cache["a"]
        cache.put("c", ByteArray(4))
        assertNotNull(cache["a"])
        assertNull(cache["b"])
        assertNotNull(cache["c"])
        assertEquals(8L, cache.size())
    }

    @Test
    fun `a content larger than all of it is not kept and drops the one it replaces`() {
        val cache = ByteCache(10)
        cache.put("a", ByteArray(4))
        cache.put("d", ByteArray(11))
        assertNull(cache["d"])
        assertNotNull(cache["a"])
        cache.put("a", ByteArray(11))
        assertNull(cache["a"])
        assertEquals(0L, cache.size())
    }

    @Test
    fun `a content put again counts once`() {
        val cache = ByteCache(10)
        cache.put("a", ByteArray(4))
        cache.put("a", ByteArray(6))
        assertEquals(6L, cache.size())
        cache.clear()
        assertNull(cache["a"])
        assertEquals(0L, cache.size())
    }
}
