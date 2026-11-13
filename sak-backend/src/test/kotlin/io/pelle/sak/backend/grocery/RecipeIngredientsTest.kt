package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.files.FileService
import io.pelle.sak.config.SakConfig
import io.pelle.sak.openrouter.OpenRouterIngredientRecognizer
import io.pelle.sak.storage.BlobContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// the prompt of OpenRouterIngredientRecognizer against the photos of grocery/recipes and their ingredients
// (<photo>.json: the names, the quantity and the unit). It asks openrouter, only with OPENROUTER_API_KEY set
// (gradle reads it from pass), a few cents a run: mise run backend:test:recipes. OPENROUTER_MODEL selects
// another model than the default. The photos go as the apps upload them
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+", disabledReason = "the photos go to openrouter only with OPENROUTER_API_KEY")
class RecipeIngredientsTest {
    @TestFactory
    fun `the ingredients of each recipe photo`(): List<DynamicTest> {
        val key = System.getenv("OPENROUTER_API_KEY")
        val model = System.getenv("OPENROUTER_MODEL")?.takeIf { it.isNotBlank() } ?: SakConfig.DEFAULT_OPENROUTER_MODEL
        val photos = Path.of(javaClass.getResource("/grocery/recipes")!!.toURI()).listDirectoryEntries("*.jpg").sorted()
        return photos.map { photo ->
            dynamicTest(photo.nameWithoutExtension) {
                val expected = expected(photo.resolveSibling("${photo.nameWithoutExtension}.json"))
                val actual = ingredients(key, model, uploaded(photo))
                val result = compare(expected, actual)
                println("${photo.nameWithoutExtension} ($model):\n" + actual.joinToString("\n") { "  $it" })
                assertTrue(result.missing.isEmpty() && result.extra.isEmpty()) {
                    "${photo.nameWithoutExtension}:\n" +
                        result.missing.joinToString("") { "  missing $it\n" } +
                        result.extra.joinToString("") { "  extra   $it\n" }
                }
            }
        }
    }

    // an ingredient as the model answers it
    data class Ingredient(
        val name: String,
        val quantity: Double?,
        val unit: String?,
    ) {
        override fun toString() = listOfNotNull(quantity?.let(::number), unit, name).joinToString(" ")
    }

    // an ingredient of a photo: the names it may have (all words of one of them in the name, a word of
    // 4 letters or more also as the start of a word: Lachs is Lachsfilet), its quantity, or the ones it
    // may have where the recipe offers a choice (40 g Butter oder 2 EL Olivenöl), and unit; an optional
    // one, the other choice, may be missing
    data class Expected(
        val names: List<String>,
        val quantities: List<Double?>,
        val unit: String?,
        val optional: Boolean = false,
    ) {
        override fun toString() =
            listOfNotNull(quantities.filterNotNull().joinToString(" or ") { number(it) }.ifEmpty { null }, unit, names.first()).joinToString(" ")
    }

    data class Result(
        // the ones of the photo the model did not answer, or with another quantity or unit
        val missing: List<String>,
        // the ones the model answered that the photo does not have
        val extra: List<Ingredient>,
    )

    private fun expected(file: Path): List<Expected> =
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("ingredients").jsonArray.map { element ->
            val ingredient = element.jsonObject
            val quantity = ingredient["quantity"]
            Expected(
                ingredient.getValue("names").jsonArray.map { it.jsonPrimitive.content },
                if (quantity is JsonArray) quantity.map { it.jsonPrimitive.doubleOrNull } else listOf(quantity?.jsonPrimitive?.doubleOrNull),
                ingredient["unit"]?.jsonPrimitive?.contentOrNull,
                ingredient["optional"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }

    // each expected one takes the first answered one of its name; it is missing without one, or when
    // its quantity or unit is another
    private fun compare(
        expected: List<Expected>,
        actual: List<Ingredient>,
    ): Result {
        val left = actual.toMutableList()
        val missing = mutableListOf<String>()
        for (ingredient in expected) {
            val match = left.firstOrNull { named(it.name, ingredient.names) }
            if (match == null) {
                if (!ingredient.optional) missing += "$ingredient"
                continue
            }
            left -= match
            val sameQuantity =
                ingredient.quantities.any { quantity ->
                    (match.quantity == null) == (quantity == null) && (match.quantity == null || abs(match.quantity - quantity!!) < 0.001)
                }
            if (!sameQuantity || !sameUnit(match, ingredient)) missing += "$ingredient (answered $match)"
        }
        return Result(missing, left)
    }

    // the unit of the photo, or the unit as part of the word of the name: 2 Knoblauchzehen is 2 Zehe
    // Knoblauch
    private fun sameUnit(
        match: Ingredient,
        ingredient: Expected,
    ): Boolean {
        val expected = unit(ingredient.unit)
        if (unit(match.unit) == expected) return true
        if (match.unit != null || ingredient.unit == null) return false
        val glued = ingredient.names.map { (words(it).joinToString("") + ingredient.unit).lowercase() }
        return words(match.name).any { word -> glued.any { word.startsWith(it) } }
    }

    private fun named(
        name: String,
        names: List<String>,
    ): Boolean {
        val words = words(name)
        return names.any { accepted ->
            words(accepted).all { word -> words.any { it == word || (word.length >= 4 && it.startsWith(word)) } }
        }
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }

    // the units the same with another spelling; a count of things has none
    private fun unit(unit: String?): String? {
        val normal = unit?.trim()?.lowercase()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return null
        return UNITS[normal] ?: normal.takeUnless { it in COUNTS }
    }

    // the photo as an app uploads it: turned upright by its exif orientation, at most MAX_IMAGE_SIZE
    // pixels on its longer side, a jpeg of quality 85 without the exif
    private fun uploaded(photo: Path): ByteArray {
        val bytes = Files.readAllBytes(photo)
        val image = upright(ImageIO.read(ByteArrayInputStream(bytes)), orientation(bytes))
        val factor = min(1.0, FileService.MAX_IMAGE_SIZE.toDouble() / max(image.width, image.height))
        val scaled = BufferedImage((image.width * factor).roundToInt(), (image.height * factor).roundToInt(), BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(image, 0, 0, scaled.width, scaled.height, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        MemoryCacheImageOutputStream(out).use { stream ->
            writer.output = stream
            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.85f
                }
            writer.write(null, IIOImage(scaled, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    // the turns of the exif orientations of a phone's camera; the mirrored ones no camera writes
    private fun upright(
        image: BufferedImage,
        orientation: Int,
    ): BufferedImage {
        val (width, height, transform) =
            when (orientation) {
                3 -> Triple(image.width, image.height, AffineTransform(-1.0, 0.0, 0.0, -1.0, image.width.toDouble(), image.height.toDouble()))
                6 -> Triple(image.height, image.width, AffineTransform(0.0, 1.0, -1.0, 0.0, image.height.toDouble(), 0.0))
                8 -> Triple(image.height, image.width, AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, image.width.toDouble()))
                else -> return image
            }
        val turned = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        turned.createGraphics().apply {
            drawImage(image, transform, null)
            dispose()
        }
        return turned
    }

    // the exif orientation of a jpeg (tag 0x0112 of IFD0 in its APP1 segment), 1 without one
    private fun orientation(jpeg: ByteArray): Int {
        val data = ByteBuffer.wrap(jpeg)
        var at = 2
        while (at + 4 <= jpeg.size && jpeg[at] == 0xFF.toByte()) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            val length = data.getShort(at + 2).toInt() and 0xFFFF
            if (marker == 0xE1 && String(jpeg, at + 4, 4, Charsets.US_ASCII) == "Exif") {
                val tiff = at + 10
                val order = if (jpeg[tiff] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val exif = ByteBuffer.wrap(jpeg).order(order)
                val ifd = tiff + exif.getInt(tiff + 4)
                repeat(exif.getShort(ifd).toInt() and 0xFFFF) { index ->
                    val entry = ifd + 2 + index * 12
                    if (exif.getShort(entry).toInt() and 0xFFFF == 0x0112) return exif.getShort(entry + 8).toInt()
                }
                return 1
            }
            // the image data starts, no exif came before it
            if (marker == 0xDA) return 1
            at += 2 + length
        }
        return 1
    }

    // the items the backend answers for the photo: what the recognizer of the deployment reads in it,
    // within the limits of an item and merged (GroceryDiscovery.items)
    private fun ingredients(
        key: String,
        model: String,
        jpeg: ByteArray,
    ): List<Ingredient> =
        GroceryDiscovery
            .items(
                OpenRouterIngredientRecognizer(SakConfig.DEFAULT_OPENROUTER_URL, key, model)
                    .ingredients(BlobContent(jpeg.size.toLong()) { jpeg.inputStream() }, "image/jpeg"),
            ).map { Ingredient(it.name, it.amount?.toDouble(), it.unit) }

    companion object {
        private val UNITS =
            mapOf(
                "zehen" to "zehe",
                "esslöffel" to "el",
                "teelöffel" to "tl",
                "gramm" to "g",
                "milliliter" to "ml",
                "liter" to "l",
                "stangen" to "stange",
                "scheiben" to "scheibe",
                "dosen" to "dose",
                "prisen" to "prise",
            )

        // a count of things, no unit: 1 Stück(e) Paprika is 1 Paprika
        private val COUNTS = setOf("stück", "stück(e)", "stück(s)", "stk")

        private fun number(value: Double) = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
