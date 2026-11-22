package io.pelle.sak.backend.todo

import io.pelle.sak.core.Catalog
import io.pelle.sak.backend.lists.ListTexts

// the texts of the todo lists (todo.backend and todo.common in i18n/texts.yml)
object TodoTexts : Catalog("todo") {
    val INVALID_ICON = text("invalid_icon")
    val FIRST_LIST = text("first_list")
    val INVALID_LIST_NAME = text("invalid_list_name")
    val INVALID_TITLE = text("invalid_title")
    val INVALID_DUE_DATE = text("invalid_due_date")
    val INVALID_PRIORITY = text("invalid_priority")
    val LIST_EXISTS = text("list_exists")
    val UNKNOWN_LIST = text("unknown_list")
    val ONLY_OWNER_SHARES = text("only_owner_shares")
    val LISTS = ListTexts(INVALID_ICON, INVALID_LIST_NAME, LIST_EXISTS, ONLY_OWNER_SHARES)
}
