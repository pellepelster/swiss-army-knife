package io.pelle.sak.auth

import io.pelle.sak.core.Catalog

// the texts of the sign-in (backend.auth in i18n/texts.yml)
object AuthTexts : Catalog("auth") {
    val INVALID_EMAIL = text("invalid_email")
    val INVALID_NONCE = text("invalid_nonce")
    val INVALID_CHALLENGE = text("invalid_challenge")
    val LINK_INVALID = text("link_invalid")
    val CODE_INVALID = text("code_invalid")
    val WRONG_PASSWORD = text("wrong_password")
    val MAIL_SUBJECT = text("mail_subject")
    val MAIL_BODY = text("mail_body")
}
