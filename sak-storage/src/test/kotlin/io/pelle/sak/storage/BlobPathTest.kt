package io.pelle.sak.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class BlobPathTest {
    @Test
    fun `a segment is no directory of its own, no step up and nothing that ends a path`() {
        for (segment in listOf("", ".", "..", "a/b", "a${Char(0)}b")) {
            shouldThrow<IllegalArgumentException> { BlobPath(listOf("x", segment)) }
        }
        BlobPath(listOf("a", "...", ".hidden", "a b", "ä")).toString() shouldBe "a/.../.hidden/a b/ä"
    }

    @Test
    fun `the staging directory is no path of a blob, below the root it is a name like any other`() {
        shouldThrow<IllegalArgumentException> { BlobPath(listOf(BlobPath.STAGING_DIRECTORY, "a")) }
        BlobPath(listOf("a", BlobPath.STAGING_DIRECTORY)).segments shouldBe listOf("a", ".tmp")
    }

    @Test
    fun `paths of the same segments are equal, resolve adds segments`() {
        BlobPath(listOf("a")).resolve("b", "c") shouldBe BlobPath(listOf("a", "b", "c"))
        BlobPath(listOf("a", "b")).hashCode() shouldBe BlobPath(listOf("a", "b")).hashCode()
        BlobPath(listOf("a", "b")) shouldNotBe BlobPath(listOf("b", "a"))
        BlobPath(emptyList()).toString() shouldBe ""
        shouldThrow<IllegalArgumentException> { BlobPath(listOf("a")).resolve("..") }
    }
}
