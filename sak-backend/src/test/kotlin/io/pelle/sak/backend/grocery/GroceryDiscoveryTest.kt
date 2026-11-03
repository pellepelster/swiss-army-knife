package io.pelle.sak.backend.grocery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.testing.testClock
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.StoreFileResult
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.testing.Media
import io.pelle.sak.openrouter.Ingredient
import io.pelle.sak.openrouter.IngredientRecognizer
import io.pelle.sak.openrouter.TextRecognitionException
import io.pelle.sak.storage.BlobContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

private const val PELLE = "pelle@pelle.io"

class GroceryDiscoveryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val files by lazy { FileService(database, tempDir.resolve("files"), testClock()) }

    // answers what the test sets, "fail" after the jpeg header fails
    private var answer = listOf(Ingredient("Mehl", 250.0, "g"))

    private val recognizer =
        object : IngredientRecognizer {
            override fun ingredients(
                image: BlobContent,
                contentType: String,
            ): List<Ingredient> {
                val bytes = image.open().use { it.readAllBytes() }
                if (String(bytes.drop(Media.JPEG.size).toByteArray()) == "fail") throw TextRecognitionException("the model failed")
                return answer
            }
        }

    private var aiEnabled = true
    private var keySet = true

    private val discovery by lazy { GroceryDiscovery(files, { recognizer.takeIf { keySet } }) { aiEnabled } }

    private fun photo(content: String = "recipe") = (files.store((Media.JPEG + content.toByteArray()).inputStream(), PELLE) as StoreFileResult.Stored).file.id

    @Test
    fun `the items of a photo of the owner, the limit asked only when the photo goes to the AI provider`() {
        val recipe = photo()
        var asked = 0
        val counting = { asked++; true }

        discovery.discover(PELLE, 4711, counting) shouldBe GroceryDiscoverResult.NotFound
        discovery.discover("anna@pelle.io", recipe, counting) shouldBe GroceryDiscoverResult.NotFound
        aiEnabled = false
        discovery.available() shouldBe false
        discovery.discover(PELLE, recipe, counting) shouldBe GroceryDiscoverResult.Disabled
        aiEnabled = true
        keySet = false
        discovery.available() shouldBe false
        discovery.discover(PELLE, recipe, counting) shouldBe GroceryDiscoverResult.NotConfigured
        keySet = true
        asked shouldBe 0

        discovery.available() shouldBe true
        discovery.discover(PELLE, recipe, counting) shouldBe GroceryDiscoverResult.Discovered(listOf(DiscoveredItem("Mehl", BigDecimal("250"), "g")))
        asked shouldBe 1
        discovery.discover(PELLE, recipe) { false } shouldBe GroceryDiscoverResult.Limited
        discovery.discover(PELLE, photo("fail")).shouldBeInstanceOf<GroceryDiscoverResult.Failed>()
    }

    @Test
    fun `a recording is no photo`() {
        val wav = (files.store(Media.wav().inputStream(), PELLE) as StoreFileResult.Stored).file.id
        discovery.discover(PELLE, wav) shouldBe GroceryDiscoverResult.NotAPhoto
    }

    @Test
    fun `what the model answers is kept within the limits of an item`() {
        GroceryDiscovery.items(
            listOf(
                Ingredient("  rote\tPaprika \n", 1.0, "  "),
                Ingredient("Salz", null, "TL"),
                Ingredient("Sahne", 0.33333, "ml"),
                Ingredient("Butter", -2.0, "g"),
                Ingredient("Zucker", 1e12, "g"),
                Ingredient("", 1.0, "g"),
                Ingredient("x".repeat(200), 2.0, "Packungen mit viel Inhalt"),
            ),
        ) shouldBe
            listOf(
                DiscoveredItem("rote Paprika", BigDecimal("1"), null),
                DiscoveredItem("Salz", null, null),
                DiscoveredItem("Sahne", BigDecimal("0.333"), "ml"),
                DiscoveredItem("Butter", null, null),
                DiscoveredItem("Zucker", null, null),
                DiscoveredItem("x".repeat(GroceryService.MAX_ITEM_NAME_LENGTH), BigDecimal("2"), "Packungen mit vi"),
            )
        GroceryDiscovery.items(List(150) { Ingredient("Item $it", null, null) }).size shouldBe GroceryDiscovery.MAX_ITEMS
    }

    @Test
    fun `an item named twice is one, its amounts of one unit added up`() {
        GroceryDiscovery.items(
            listOf(
                Ingredient("Zwiebel", 1.0, null),
                Ingredient("Erdnussöl", 2.0, "EL"),
                Ingredient("Salz", null, null),
                Ingredient("zwiebel", 1.0, null),
                Ingredient("Erdnussöl", 1.0, "el"),
                Ingredient("Salz", 1.0, "TL"),
                Ingredient("Butter", 50.0, "g"),
                Ingredient("Butter", 2.0, "EL"),
                Ingredient("Pfeffer", null, null),
                Ingredient("Pfeffer", null, null),
                Ingredient("Zucker", 9_999_999.0, "g"),
                Ingredient("Zucker", 5.0, "g"),
                // the singular and the plural, under the plural
                Ingredient("Ei", 1.0, null),
                Ingredient("Eier", 4.0, null),
                Ingredient("Tomaten", 2.0, null),
                Ingredient("Tomate", 1.0, null),
                Ingredient("Eigelb", 1.0, null),
                Ingredient("Eier (Größe M)", 2.0, null),
                // water from the tap is nothing to buy, other water is
                Ingredient("kaltes Wasser", 1.0, "EL"),
                Ingredient("Wasser", null, null),
                Ingredient("Mineralwasser", 1.0, "l"),
            ),
        ) shouldBe
            listOf(
                DiscoveredItem("Zwiebel", BigDecimal("2"), null),
                DiscoveredItem("Erdnussöl", BigDecimal("3"), "EL"),
                DiscoveredItem("Salz", BigDecimal("1"), "TL"),
                DiscoveredItem("Butter", BigDecimal("50"), "g"),
                DiscoveredItem("Butter", BigDecimal("2"), "EL"),
                DiscoveredItem("Pfeffer", null, null),
                DiscoveredItem("Zucker", GroceryService.MAX_AMOUNT, "g"),
                DiscoveredItem("Eier", BigDecimal("7"), null),
                DiscoveredItem("Tomaten", BigDecimal("3"), null),
                DiscoveredItem("Eigelb", BigDecimal("1"), null),
                DiscoveredItem("Mineralwasser", BigDecimal("1"), "l"),
            )
    }
}
