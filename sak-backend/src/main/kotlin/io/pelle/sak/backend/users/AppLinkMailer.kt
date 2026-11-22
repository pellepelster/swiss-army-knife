package io.pelle.sak.backend.users

import io.pelle.sak.api.logCause
import io.pelle.sak.api.logAddress
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.api.Language
import io.pelle.sak.api.Text
import io.pelle.sak.api.render
import io.pelle.sak.auth.LoginApp
import io.pelle.sak.auth.MailSender

private val logger = KotlinLogging.logger {}

// the paths of the pages that download the android apps, without a session
const val GROCERY_APP_DOWNLOAD_PATH = "/grocery/download/app"
const val NOTES_APP_DOWNLOAD_PATH = "/notes/download/app"
const val TODO_APP_DOWNLOAD_PATH = "/todo/download/app"

// sends the links to the android apps by e-mail, from the mail server of the login, e.g. to a user
// an admin just added
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
                "todo" to "$url$TODO_APP_DOWNLOAD_PATH",
            ).render(language),
        )
    }
}

sealed interface AppLinkSendResult {
    data object Sent : AppLinkSendResult

    // the deployment has no mail server (single user mode)
    data object NoMailServer : AppLinkSendResult

    // the address is no user and no admin of the deployment
    data object NotAUser : AppLinkSendResult

    // the mail server did not take the e-mail
    data object NotSent : AppLinkSendResult
}

// sends a user or an admin of the deployment the links of the android apps; without a mailer, the
// deployment has no mail server, nothing is sent
class AppLinkService(
    private val users: UserService,
    private val mailer: AppLinkMailer?,
) {
    // the mail in the language given, the one of the admin who sends it: the language of the user is
    // not known
    fun send(
        email: String,
        language: Language,
    ): AppLinkSendResult {
        val address = UserService.normalize(email)
        if (mailer == null) return AppLinkSendResult.NoMailServer
        if (users.users().none { it.email == address }) return AppLinkSendResult.NotAUser
        return try {
            mailer.send(address, language)
            logger.info { "sent the links of the android apps to ${logAddress(address)}" }
            AppLinkSendResult.Sent
        } catch (e: Exception) {
            logger.warn(logCause(e)) { "could not send the links of the android apps to ${logAddress(address)}" }
            AppLinkSendResult.NotSent
        }
    }
}
