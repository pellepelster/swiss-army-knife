package io.pelle.sak.api.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.api.storage.model.BlobPath
import org.junit.jupiter.api.Test

class BlobPathTest {
    @Test
    fun `a segment is no directory of its own, no step up and nothing that ends a path`() {
        for (segment in listOf("", ".", "..", "a/b", "a${Char(0)}b")) {
            shouldThrow<IllegalArgumentException> { BlobPath(listOf("x", segment)) }
        }
        BlobPath(listOf("a", "...", ".hidden", "a b", "ä")).toString() shouldBe "a/.../.hidden/a b/ä"
    }

    // a longer one fails at once, not when the blob is moved into place
    @Test
    fun `paths of the same segments are equal, resolve adds segments`() {
        BlobPath(listOf("a")).resolve("b", "c") shouldBe BlobPath(listOf("a", "b", "c"))
        BlobPath(listOf("a", "b")).hashCode() shouldBe BlobPath(listOf("a", "b")).hashCode()
        BlobPath(listOf("a", "b")) shouldNotBe BlobPath(listOf("b", "a"))
        BlobPath(emptyList()).toString() shouldBe ""
        shouldThrow<IllegalArgumentException> { BlobPath(listOf("a")).resolve("..") }
    }
}
