package io.pelle.sak.dns

import io.pelle.sak.core.Catalog

// the texts of the checks of the domains (backend.dns in i18n/texts.yml)
object DnsTexts : Catalog("dns") {
    val NOT_CHECKED = text("not_checked")
    val NO_ADDRESSES = text("no_addresses")
    val NO_DOMAINS = text("no_domains")
    val WRONG_DOMAINS = text("wrong_domains")
    val UNANSWERED_DOMAINS = text("unanswered_domains")
    val RIGHT_DOMAINS = text("right_domains")
    val ANSWERS = text("answers")
    val NO_ANSWER = text("no_answer")
    val NO_ANSWER_FROM = text("no_answer_from")
    val ANSWER = text("answer")
    val RECORDS = text("records")
    val NO_RECORDS = text("no_records")
}
