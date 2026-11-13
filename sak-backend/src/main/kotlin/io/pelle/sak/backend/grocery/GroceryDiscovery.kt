package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.grocery.GroceryService.Companion.MAX_AMOUNT
import io.pelle.sak.backend.grocery.GroceryService.Companion.MAX_ITEM_NAME_LENGTH
import io.pelle.sak.backend.grocery.GroceryService.Companion.MAX_UNIT_LENGTH
import io.pelle.sak.openrouter.Ingredient
import io.pelle.sak.openrouter.IngredientRecognizer
import io.pelle.sak.openrouter.TextRecognitionException
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

// an item the AI read in a photo, within the limits of an item (GroceryService.itemValues)
data class DiscoveredItem(
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
)

sealed interface GroceryDiscoverResult {
    data class Discovered(
        val items: List<DiscoveredItem>,
    ) : GroceryDiscoverResult

    data object NotFound : GroceryDiscoverResult

    // only the items in photos are discovered
    data object NotAPhoto : GroceryDiscoverResult

    // no api key is set
    data object NotConfigured : GroceryDiscoverResult

    // the AI features are turned off in the settings (GrocerySettingsService.AI and what it requires)
    data object Disabled : GroceryDiscoverResult

    // the photo would go to the AI provider, but the owner asked for too many lately
    data object Limited : GroceryDiscoverResult

    // the model failed, or the content of the file could not be read
    data class Failed(
        val cause: Exception,
    ) : GroceryDiscoverResult
}

// discovers the items to buy in a photo, of a recipe or a shopping list (GROCERY-16). Nothing is
// added: the user confirms the items first. A photo is read again every time, nothing is kept
class GroceryDiscovery(
    private val files: FileService,
    // asked for each photo, null while no api key is set (OpenRouterManager)
    private val recognizer: () -> IngredientRecognizer?,
    // whether the photos may be sent to the AI provider at all
    private val aiEnabled: () -> Boolean,
) {
    // the AI features on and an api key set; every user may know this, the switches are for the
    // admins
    fun available(): Boolean = aiEnabled() && recognizer() != null

    // mayAsk is asked only right before the photo goes to the AI provider, so the limit counts
    // nothing else
    fun discover(
        owner: String,
        fileId: Int,
        mayAsk: () -> Boolean = { true },
    ): GroceryDiscoverResult {
        val file = files.byId(owner, fileId) ?: return GroceryDiscoverResult.NotFound
        if (!file.contentType.startsWith("image/")) return GroceryDiscoverResult.NotAPhoto
        if (!aiEnabled()) return GroceryDiscoverResult.Disabled
        val recognizer = recognizer() ?: return GroceryDiscoverResult.NotConfigured
        val content = files.content(file) ?: return GroceryDiscoverResult.Failed(IOException("the content of file ${file.id} is not in the storage"))
        if (!mayAsk()) return GroceryDiscoverResult.Limited
        val ingredients =
            try {
                recognizer.ingredients(content, file.contentType)
            } catch (e: TextRecognitionException) {
                return GroceryDiscoverResult.Failed(e)
            } catch (e: IOException) {
                return GroceryDiscoverResult.Failed(e)
            }
        return GroceryDiscoverResult.Discovered(items(ingredients))
    }

    companion object {
        // more items of one photo are left out
        const val MAX_ITEMS = 100

        // the answer of the model within the limits of an item: names and units cleaned and cut,
        // amounts rounded to 3 decimal places or dropped when out of range, a unit only with an
        // amount; no name or tap water is left out. An ingredient in several parts of a recipe (e.g.
        // dough and filling) is merged into one here, the model lists each mention
        internal fun items(ingredients: List<Ingredient>): List<DiscoveredItem> =
            merged(
                ingredients.mapNotNull { ingredient ->
                    val name = clean(ingredient.name, MAX_ITEM_NAME_LENGTH)?.takeUnless(::water) ?: return@mapNotNull null
                    val amount =
                        ingredient.quantity
                            ?.takeIf { it.isFinite() }
                            ?.let { BigDecimal.valueOf(it).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros() }
                            ?.takeIf { it.signum() > 0 && it <= MAX_AMOUNT }
                    val unit = amount?.let { ingredient.unit?.let { unit -> clean(unit, MAX_UNIT_LENGTH) } }
                    DiscoveredItem(name, amount?.let { if (it.scale() < 0) it.setScale(0) else it }, unit)
                },
            ).take(MAX_ITEMS)

        // the endings of the plural of a german name (Ei, Eier; Zwiebel, Zwiebeln; Tomate, Tomaten) and of
        // an english one (egg, eggs)
        private val PLURALS = listOf("en", "er", "n", "e", "s")

        // the items of one name (any case, singular or plural) as one at the place of the first, under
        // the longer name (Eier): the amounts of one unit added up (at most MAX_AMOUNT), one without an
        // amount dropped when another has one; other units stay items of their own
        private fun merged(items: List<DiscoveredItem>): List<DiscoveredItem> {
            val names = mutableListOf<String>()

            // the first name seen that it is one with; brackets do not count (Eier, Eier (Größe M))
            fun key(item: DiscoveredItem): String {
                val name = bare(item.name)
                return names.firstOrNull { it == name || plural(it, name) || plural(name, it) } ?: name.also { names += it }
            }
            val keys = items.map(::key)
            val counted = items.indices.filter { items[it].amount != null }.map { keys[it] }.toSet()
            val merged = LinkedHashMap<Pair<String, String?>, DiscoveredItem>()
            items.forEachIndexed { index, item ->
                if (item.amount == null && keys[index] in counted) return@forEachIndexed
                val at = keys[index] to item.unit?.lowercase()
                val before = merged[at]
                merged[at] =
                    when {
                        before == null -> item
                        before.amount == null || item.amount == null -> before
                        else ->
                            before.copy(
                                name = maxOf(before.name, item.name, compareBy { bare(it).length }),
                                amount = (before.amount + item.amount).min(MAX_AMOUNT),
                            )
                    }
            }
            return merged.values.toList()
        }

        // the name without case and what is in brackets
        private fun bare(name: String) = name.lowercase().replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()

        // water from the tap, nothing to buy; the model keeps it now and then (kaltes Wasser)
        private val WATER = Regex("(?i)((kalt|warm|heiß|heiss|lauwarm)(es|em)? )?(leitungs)?wasser|((cold|warm|hot|lukewarm|tap) )?water")

        private fun water(name: String) = WATER.matches(name)

        private fun plural(
            singular: String,
            name: String,
        ) = singular.length >= 2 && PLURALS.any { name == singular + it }

        private fun clean(
            text: String,
            max: Int,
        ): String? =
            text
                .replace(Regex("\\s+"), " ")
                .filterNot { it.isISOControl() }
                .trim()
                .let { if (it.length > max) it.take(max).trimEnd() else it }
                .ifEmpty { null }
    }
}
