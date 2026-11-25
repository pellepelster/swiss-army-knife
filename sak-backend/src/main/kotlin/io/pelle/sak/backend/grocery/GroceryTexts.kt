package io.pelle.sak.backend.grocery

import io.pelle.sak.core.Catalog
import io.pelle.sak.backend.lists.ListTexts

// the texts of the grocery lists (grocery.backend in i18n/texts.yml)
object GroceryTexts : Catalog("grocery") {
    val NOT_A_PHOTO = text("not_a_photo")
    val FIRST_LIST = text("first_list")
    val TOO_MANY_DISCOVERIES = text("too_many_discoveries")
    val DISCOVERY_FAILED = text("discovery_failed")
    val AI_LABEL = text("settings.ai.label")
    val AI_DESCRIPTION = text("settings.ai.description")
    val SCOPE_LABEL = text("settings.label")
    val FRUIT_VEGETABLES = text("category.fruit_vegetables")
    val BAKERY = text("category.bakery")
    val DAIRY = text("category.dairy")
    val MEAT_FISH = text("category.meat_fish")
    val PANTRY = text("category.pantry")
    val FROZEN = text("category.frozen")
    val DRINKS = text("category.drinks")
    val HOUSEHOLD = text("category.household")
    val OTHER = text("category.other")
    val INVALID_ICON = text("invalid_icon")
    val INVALID_LIST_NAME = text("invalid_list_name")
    val INVALID_ITEM_NAME = text("invalid_item_name")
    val INVALID_AMOUNT = text("invalid_amount")
    val INVALID_UNIT = text("invalid_unit")
    val UNIT_WITHOUT_AMOUNT = text("unit_without_amount")
    val UNKNOWN_CATEGORY = text("unknown_category")
    val INVALID_CATEGORY_NAME = text("invalid_category_name")
    val CATEGORY_EXISTS = text("category_exists")
    val TOO_MANY_CATEGORIES = text("too_many_categories")
    val INVALID_CATEGORY_ORDER = text("invalid_category_order")
    val LIST_EXISTS = text("list_exists")
    val UNKNOWN_LIST = text("unknown_list")
    val ONLY_OWNER_SHARES = text("only_owner_shares")
    val LISTS = ListTexts(INVALID_ICON, INVALID_LIST_NAME, LIST_EXISTS, ONLY_OWNER_SHARES)
}
