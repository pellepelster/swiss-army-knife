package io.pelle.sak.model.s3

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class S3BucketServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)

    private val service by lazy { S3BucketService(SakDatabase.open(tempDir.resolve("sak.db")), clock, reservedNames = setOf("console")) }

    @Test
    fun `creates buckets with a key pair of their own`() {
        val first = service.create("pelle.io").shouldBeInstanceOf<CreateBucketResult.Created>().bucket
        val second = service.create("backups").shouldBeInstanceOf<CreateBucketResult.Created>().bucket

        first.name shouldBe "pelle.io"
        first.createdAt shouldBe clock.instant()
        first.accessKeyId.length shouldBe 20
        first.secretAccessKey.length shouldBe 40
        first.accessKeyId shouldNotBe second.accessKeyId
        first.secretAccessKey shouldNotBe second.secretAccessKey

        service.all().map { it.name } shouldBe listOf("backups", "pelle.io")
        service.byName("pelle.io") shouldBe first
        service.byAccessKeyId(second.accessKeyId) shouldBe second
        service.byName("unknown") shouldBe null
    }

    @Test
    fun `rejects taken, invalid and reserved names`() {
        service.create("pelle.io").shouldBeInstanceOf<CreateBucketResult.Created>()

        service.create("pelle.io") shouldBe CreateBucketResult.NameTaken
        for (name in listOf("ab", "Upper", "-start", "end-", "a..b", "192.168.0.1", "a".repeat(64), "under_score", "console")) {
            service.create(name) shouldBe CreateBucketResult.InvalidName
        }
        service.all().map { it.name } shouldBe listOf("pelle.io")
    }
}
