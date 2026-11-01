package io.pelle.sak.backend.users

import io.pelle.sak.api.Language
import io.pelle.sak.api.Text
import io.pelle.sak.api.render
import io.pelle.sak.auth.LoginApp
import io.pelle.sak.auth.MailSender

// the path of the page that downloads the android app of the grocery lists, without a session
const val GROCERY_APP_DOWNLOAD_PATH = "/grocery/download/app"

// sends the link to the android app of the grocery lists by e-mail, from the mail server of the
// login, e.g. to a user an admin just added
class AppLinkMailer(
    private val mailSender: MailSender,
    private val app: LoginApp,
) {
    fun send(
        to: String,
        language: Language,
    ) = mailSender.send(
        to,
        Text(UsersTexts.APP_LINK_SUBJECT, "app" to app.name).render(language),
        Text(UsersTexts.APP_LINK_BODY, "app" to app.name, "link" to "${app.url.trimEnd('/')}$GROCERY_APP_DOWNLOAD_PATH").render(language),
    )
}
