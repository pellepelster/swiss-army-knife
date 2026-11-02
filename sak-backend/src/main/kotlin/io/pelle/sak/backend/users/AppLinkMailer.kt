package io.pelle.sak.backend.users

import io.pelle.sak.api.Language
import io.pelle.sak.api.Text
import io.pelle.sak.api.render
import io.pelle.sak.auth.LoginApp
import io.pelle.sak.auth.MailSender

// the paths of the pages that download the android apps, without a session
const val GROCERY_APP_DOWNLOAD_PATH = "/grocery/download/app"
const val NOTES_APP_DOWNLOAD_PATH = "/notes/download/app"

// sends the links to the android apps (the grocery lists and the notes) by e-mail, from the mail
// server of the login, e.g. to a user an admin just added
class AppLinkMailer(
    private val mailSender: MailSender,
    private val app: LoginApp,
) {
    fun send(
        to: String,
        language: Language,
    ) {
        val url = app.url.trimEnd('/')
        mailSender.send(
            to,
            Text(UsersTexts.APP_LINK_SUBJECT, "app" to app.name).render(language),
            Text(
                UsersTexts.APP_LINK_BODY,
                "app" to app.name,
                "grocery" to "$url$GROCERY_APP_DOWNLOAD_PATH",
                "notes" to "$url$NOTES_APP_DOWNLOAD_PATH",
            ).render(language),
        )
    }
}
