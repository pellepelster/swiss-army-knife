package io.pelle.sak.openrouter

import io.pelle.sak.storage.BlobContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.http.HttpClient
import java.time.Duration

// reads the ingredients of a recipe in an image with a vision model through openrouter's chat
// completions, as json of a schema (structured outputs, where the provider takes them); the image goes
// along as data url, streamed from the storage (base64Body). Only providers that do not collect the
// data are asked
class OpenRouterIngredientRecognizer(
    url: String,
    private val apiToken: String,
    private val model: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(90),
) : IngredientRecognizer {
    private val url = url.trimEnd('/')

    override fun ingredients(
        image: BlobContent,
        contentType: String,
    ): List<Ingredient> {
        val content =
            chatCompletion(httpClient, url, apiToken, timeout, request(contentType).toString(), image).content
                ?: throw TextRecognitionException("openrouter answered without a choice")
        return parse(content)
    }

    private fun request(contentType: String): JsonObject =
        buildJsonObject {
            put("model", model)
            put("temperature", 0)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    // the prompt first, then the image, as openrouter recommends
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", PROMPT)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:$contentType;base64,$BASE64_DATA") }
                        }
                    }
                }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                put("json_schema", SCHEMA)
            }
            // no require_parameters: it leaves out every provider that does not take all of them, and no
            // provider of a model may take temperature (anthropic/claude-haiku-5.5 has none); one that
            // ignores the schema still answers the json the prompt asks for
            putJsonObject("provider") { put("data_collection", "deny") }
        }

    companion object {
        // the ingredients of the answer of the model; a model that wraps the json in a code block is
        // taken too
        internal fun parse(content: String): List<Ingredient> {
            val json = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val ingredients =
                try {
                    Json.parseToJsonElement(json).jsonObject["ingredients"]?.jsonArray
                } catch (e: IllegalArgumentException) {
                    throw TextRecognitionException("the model answered no json of ingredients", e)
                } ?: throw TextRecognitionException("the model answered no ingredients")
            return ingredients.mapNotNull { element ->
                val ingredient = element as? JsonObject ?: return@mapNotNull null
                val name = (ingredient["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                Ingredient(
                    name,
                    (ingredient["quantity"] as? JsonPrimitive)?.doubleOrNull,
                    (ingredient["unit"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }

        // name, quantity and unit of each ingredient, quantity and unit null when there is none
        private val SCHEMA =
            buildJsonObject {
                put("name", "ingredients")
                put("strict", true)
                putJsonObject("schema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("ingredients") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("name") { put("type", "string") }
                                    putJsonObject("quantity") { put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null")))) }
                                    putJsonObject("unit") { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }
                                }
                                putJsonArray("required") {
                                    add("name")
                                    add("quantity")
                                    add("unit")
                                }
                                put("additionalProperties", false)
                            }
                        }
                    }
                    putJsonArray("required") { add("ingredients") }
                    put("additionalProperties", false)
                }
            }

        // the rules come from the photos of grocery/recipes (RecipeIngredientsTest of sak-backend)
        const val PROMPT = """The image shows a recipe, e.g. a printout, a cookbook page or a screen, or a shopping list, e.g. a
handwritten note. Extract the ingredients of the recipe, or the items of the shopping list, for a shopping list.

Which recipe:
- Only the recipe the image is centred on. Ignore other recipes that are only partly visible, e.g. on
  the facing page, and ignore the preparation steps, nutrition facts and kitchen tools.
- The image may be rotated, blurry or laid out in columns. The ingredient list may be split into
  sections (e.g. "Für den Teig", "Für die Füllung") or written as one line separated by "•" or commas.
  Read every section.

For each ingredient:
- name: the ingredient as written, in the recipe's language, without the amount, the unit, notes on
  preparation ("in Würfel geschnitten", "zum Abschmecken") or the size of an egg ("Größe M"). Keep the
  words that change what is bought ("rote Paprika", "tiefgekühlter Blattspinat", "Mehl (Type 550)").
- quantity: a positive number, at most 3 decimal places. Write fractions as decimals (½ → 0.5). For a
  range use the larger value (1-2 EL → 2). null when the recipe gives none ("etwas", "Salz",
  "nach Belieben").
- unit: the unit as the recipe writes it, shortened the usual way: g, kg, ml, l, EL, TL, Prise, Dose,
  Stange, Scheibe, Zehe, Bund, Becher, Packung. null for a count of things ("4 Eier", "1 Zwiebel",
  "1 Stück(e) Paprika" → quantity 1, unit null), and null when the quantity is null. A unit glued to
  the name is the unit ("2 Knoblauchzehen" → quantity 2, unit Zehe, name Knoblauch).
- A package size in brackets belongs to neither ("1 Dose Tomatenmark (125 g)" → 1 Dose Tomatenmark).

Special cases:
- Alternatives: take the first one ("40 g Butter oder 2 EL Olivenöl" → 40 g Butter; "Rinderbrühe,
  Hühnerbrühe oder Gemüsebrühe" → Rinderbrühe).
- Several ingredients in one line become one entry each ("Salz und Pfeffer" → Salz, Pfeffer).
- The same ingredient in several sections is listed once. Add up the quantities when the units are the
  same; otherwise keep the entry with a quantity.
- Leave out tap water and ingredients only used for greasing or dusting ("etwas Butter für die Form").
- Do not guess what you cannot read. Set a quantity you cannot read to null, and leave out an
  ingredient whose name you cannot read.

Answer with the ingredients as JSON, e.g.
{"ingredients": [{"name": "Mehl", "quantity": 250, "unit": "g"}, {"name": "Salz", "quantity": null, "unit": null}]}
If the image shows neither a recipe nor a shopping list, answer {"ingredients": []}."""
    }
}
