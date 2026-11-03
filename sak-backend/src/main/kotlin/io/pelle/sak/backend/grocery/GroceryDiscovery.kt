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

// an item the AI read in a photo, within the limits of an item (GroceryService.check)
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

// discovers the items to buy in a photo of the file storage, the ingredients of a recipe or the items
// of a shopping list (GROCERY-16). Nothing is added: the user confirms the items first, with their
// corrections. A photo is read anew every time, the items are not kept
class GroceryDiscovery(
    private val files: FileService,
    // asked for each photo, null while no api key is set (OpenRouterManager)
    private val recognizer: () -> IngredientRecognizer?,
    // whether the photos may be sent to the AI provider at all
    private val aiEnabled: () -> Boolean,
) {
    // whether the items can be discovered now: the AI features turned on and an api key set; what every
    // user of the grocery lists may know, the switches themselves are for the admins
    fun available(): Boolean = aiEnabled() && recognizer() != null

    // the items in a photo of the owner, the address that uploaded it. mayAsk is asked only right
    // before the photo goes to the AI provider, so a limit of what that costs counts nothing else
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
        // the most items of one photo, more are left out
        const val MAX_ITEMS = 100

        // what the model answered within the limits of an item: a name without control characters
        // and the blanks around it, cut at its longest; an amount rounded to 3 decimal places, none
        // when it is out of range; a unit only with an amount, cut at its longest. One without a
        // name is left out
        internal fun items(ingredients: List<Ingredient>): List<DiscoveredItem> =
            ingredients
                .mapNotNull { ingredient ->
                    val name = clean(ingredient.name, MAX_ITEM_NAME_LENGTH) ?: return@mapNotNull null
                    val amount =
                        ingredient.quantity
                            ?.takeIf { it.isFinite() }
                            ?.let { BigDecimal.valueOf(it).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros() }
                            ?.takeIf { it.signum() > 0 && it <= MAX_AMOUNT }
                    val unit = amount?.let { ingredient.unit?.let { unit -> clean(unit, MAX_UNIT_LENGTH) } }
                    DiscoveredItem(name, amount?.let { if (it.scale() < 0) it.setScale(0) else it }, unit)
                }.take(MAX_ITEMS)

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
