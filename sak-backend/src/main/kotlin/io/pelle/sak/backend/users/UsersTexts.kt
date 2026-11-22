package io.pelle.sak.backend.users

import io.pelle.sak.core.Catalog

// the texts of the users (backend.users in i18n/texts.yml)
object UsersTexts : Catalog("users") {
    val INVALID_EMAIL = text("invalid_email")
    val INVALID_NAME = text("invalid_name")
    val EXISTS = text("exists")
    val IS_ADMIN = text("is_admin")
    val NOT_A_USER = text("not_a_user")
    val NO_MAIL_SERVER = text("no_mail_server")
    val APP_LINK_NOT_SENT = text("app_link_not_sent")
    val APP_LINK_SUBJECT = text("app_link_subject")
    val APP_LINK_BODY = text("app_link_body")
}
