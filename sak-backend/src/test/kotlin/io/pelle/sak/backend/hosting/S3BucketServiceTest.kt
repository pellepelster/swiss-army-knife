package io.pelle.sak.backend.hosting

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.RedirectRepository
import io.pelle.sak.model.ingress.RedirectTarget
import io.pelle.sak.backend.DomainRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import io.pelle.sak.api.testing.testClock
import io.pelle.sak.backend.secrets.SecretType
import io.pelle.sak.backend.testSecrets

class S3BucketServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = testClock()

    private val changes = mutableListOf<String>()

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val secrets by lazy { testSecrets(database) }

    private val service by lazy {
        S3BucketService(
            database,
            secrets,
            clock,
            reservedNames = setOf("console"),
            domains = domains(database, "console.example.org"),
        ).apply { onChange { changes.add("changed") } }
    }

    // the domain reserved for the apps, and redirected.example.org redirected
    private fun domains(
        database: SakDatabase,
        reserved: String,
    ) = DomainRegistry(database, setOf(reserved)).also {
        RedirectRepository(database).insert("redirected.example.org", RedirectTarget.Domain("example.net"))
    }

    @Test
    fun `creates buckets with a key pair of their own`() {
        val first = service.create("pelle-io").shouldBeInstanceOf<CreateBucketResult.Created>().bucket
        val second = service.create("backups").shouldBeInstanceOf<CreateBucketResult.Created>().bucket

        first.name shouldBe "pelle-io"
        first.createdAt shouldBe clock.instant()
        first.accessKeyId.length shouldBe 20
        val firstSecret = service.keyPair("pelle-io")!!.second.value
        firstSecret.length shouldBe 40
        first.accessKeyId shouldNotBe second.accessKeyId
        firstSecret shouldNotBe service.keyPair("backups")!!.second.value
        // kept encrypted with the secrets, sigv4 gets it from there
        service.secretAccessKey(first.accessKeyId)!!.value shouldBe firstSecret
        secrets.get(SecretType.S3_SECRET_ACCESS_KEY, "pelle-io")!!.value shouldBe firstSecret

        service.all().map { it.name } shouldBe listOf("backups", "pelle-io")
        service.byName("pelle-io") shouldBe first
        service.byAccessKeyId(second.accessKeyId) shouldBe second
        service.byName("unknown") shouldBe null
    }

    @Test
    fun `rejects taken, invalid and reserved names`() {
        service.create("pelle-io").shouldBeInstanceOf<CreateBucketResult.Created>()

        service.create("pelle-io") shouldBe CreateBucketResult.NameTaken
        for (name in listOf("ab", "Upper", "-start", "end-", "dot.ted", "a..b", "192.168.0.1", "a".repeat(64), "under_score", "console")) {
            service.create(name) shouldBe CreateBucketResult.InvalidName
        }
        service.all().map { it.name } shouldBe listOf("pelle-io")
    }

    @Test
    fun `serves every bucket at its subdomain of the root domain as well`() {
        val buckets =
            SakDatabase.open(tempDir.resolve("root.db")).let { database ->
                S3BucketService(
                database,
                testSecrets(database),
                clock,
                baseDomain = "example.org",
                reservedNames = setOf("sak"),
                domains = domains(database, "sak.example.org"),
            )
            }
        val site = buckets.create("site", listOf("example.com")).shouldBeInstanceOf<CreateBucketResult.Created>().bucket
        val private = buckets.create("private").shouldBeInstanceOf<CreateBucketResult.Created>().bucket

        buckets.subdomain("site") shouldBe "site.example.org"
        buckets.servedDomains(site) shouldBe listOf("example.com", "site.example.org")
        buckets.servedDomains(private) shouldBe listOf("private.example.org")
        buckets.bucketOfDomain("Private.Example.org") shouldBe "private"
        buckets.bucketOfDomain("unknown.example.org") shouldBe null
        buckets.bucketOfDomain("www.private.example.org") shouldBe null

        // the subdomain of another bucket is taken, the own one is no other domain
        buckets.updateDomains("site", listOf("private.example.org")) shouldBe UpdateDomainsResult.DomainTaken
        buckets.updateDomains("site", listOf("site.example.org")).shouldBeInstanceOf<UpdateDomainsResult.Updated>()
        buckets.servedDomains(buckets.byName("site")!!) shouldBe listOf("site.example.org")

        // no name whose subdomain is served already, sak.<base_domain> is the frontend's
        buckets.create("sak") shouldBe CreateBucketResult.InvalidName
        buckets.create("redirected") shouldBe CreateBucketResult.SubdomainTaken
        buckets.create("other", listOf("taken.example.org")).shouldBeInstanceOf<CreateBucketResult.Created>()
        buckets.create("taken") shouldBe CreateBucketResult.SubdomainTaken
    }

    @Test
    fun `serves buckets at their domains, in their order`() {
        val bucket = service.create("site", listOf("WWW.example.org", "example.org", "www.example.org"))
        bucket.shouldBeInstanceOf<CreateBucketResult.Created>().bucket.domains shouldBe listOf("www.example.org", "example.org")
        service.create("private").shouldBeInstanceOf<CreateBucketResult.Created>()

        service.byName("site")!!.domains shouldBe listOf("www.example.org", "example.org")
        service.all().associate { it.name to it.domains } shouldBe mapOf("private" to emptyList(), "site" to listOf("www.example.org", "example.org"))
        service.bucketOfDomain("Example.org") shouldBe "site"
        service.bucketOfDomain("other.example.org") shouldBe null
        changes.size shouldBe 1
    }

    @Test
    fun `rejects domains that are invalid, reserved or another bucket's`() {
        service.create("site", listOf("example.org")).shouldBeInstanceOf<CreateBucketResult.Created>()

        service.create("other", listOf("example.org")) shouldBe CreateBucketResult.DomainTaken
        service.create("other", listOf("console.example.org")) shouldBe CreateBucketResult.InvalidDomain
        service.create("other", listOf("redirected.example.org")) shouldBe CreateBucketResult.InvalidDomain
        service.create("other", listOf("no domain")) shouldBe CreateBucketResult.InvalidDomain
        service.create("site", listOf("other.example.org")) shouldBe CreateBucketResult.NameTaken
        service.all().map { it.name } shouldBe listOf("site")
    }

    @Test
    fun `replaces the domains of a bucket`() {
        service.create("site", listOf("example.org"))
        service.create("other", listOf("other.example.org"))
        changes.clear()

        service.updateDomains("site", listOf("www.example.org", "example.org")).shouldBeInstanceOf<UpdateDomainsResult.Updated>()
            .bucket.domains shouldBe listOf("www.example.org", "example.org")
        service.byName("site")!!.domains shouldBe listOf("www.example.org", "example.org")

        service.updateDomains("site", listOf("other.example.org")) shouldBe UpdateDomainsResult.DomainTaken
        service.updateDomains("site", listOf("console.example.org")) shouldBe UpdateDomainsResult.InvalidDomain
        service.updateDomains("unknown", emptyList()) shouldBe UpdateDomainsResult.NotFound
        service.byName("site")!!.domains shouldBe listOf("www.example.org", "example.org")

        service.updateDomains("site", emptyList()).shouldBeInstanceOf<UpdateDomainsResult.Updated>()
        service.byName("site")!!.domains shouldBe emptyList()
        changes.size shouldBe 2
    }

    @Test
    fun `deletes buckets softly, their name stays taken and their domains are free`() {
        service.create("site", listOf("example.com"))
        changes.clear()

        service.delete("site") shouldBe DeleteBucketResult.Deleted
        service.delete("site") shouldBe DeleteBucketResult.NotFound
        changes shouldBe listOf("changed")

        service.byName("site") shouldBe null
        service.all() shouldBe emptyList()
        service.create("site") shouldBe CreateBucketResult.NameTaken
        service.create("other", listOf("example.com")).shouldBeInstanceOf<CreateBucketResult.Created>()
    }
}
