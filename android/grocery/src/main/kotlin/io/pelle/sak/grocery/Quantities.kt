package io.pelle.sak.grocery

import java.math.BigDecimal

// the quantity of an item (GROCERY-15), typed in its dialog and for the items of a photo (GROCERY-16)

// the units to choose from; an item stores the label in the language of the phone, e.g. EL in
// german. A unit an item has that is none of them stays one to choose
val UNITS =
    listOf(
        R.string.grocery_quantity_units_g,
        R.string.grocery_quantity_units_kg,
        R.string.grocery_quantity_units_ml,
        R.string.grocery_quantity_units_l,
        R.string.grocery_quantity_units_tbsp,
        R.string.grocery_quantity_units_tsp,
        R.string.grocery_quantity_units_pinch,
        R.string.grocery_quantity_units_pack,
        R.string.grocery_quantity_units_can,
        R.string.grocery_quantity_units_bunch,
        R.string.grocery_quantity_units_piece,
    )

// the largest amount the backend takes (GroceryService.MAX_AMOUNT)
val MAX_AMOUNT = BigDecimal("9999999.999")

// digits with a point or a comma and at most 3 decimal places, as the backend takes them
private val AMOUNT = Regex("""\d+([.,]\d{0,3})?|[.,]\d{1,3}""")

// the amount typed: None for an empty field, Of a positive number up to MAX_AMOUNT with at most 3
// decimal places, else Invalid
sealed interface Amount {
    data object None : Amount

    data class Of(
        val value: Double,
    ) : Amount

    data object Invalid : Amount
}

fun amountOf(text: String): Amount {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return Amount.None
    if (!AMOUNT.matches(trimmed)) return Amount.Invalid
    val number = BigDecimal(trimmed.replace(',', '.').let { if (it.startsWith('.')) "0$it" else it })
    return if (number.signum() <= 0 || number > MAX_AMOUNT) Amount.Invalid else Amount.Of(number.toDouble())
}

// an amount as it shows and is typed: 1.5, 2; no trailing zeros, empty for none
fun amountText(amount: Double?): String = amount?.let { BigDecimal(it.toString()).stripTrailingZeros().toPlainString() }.orEmpty()
