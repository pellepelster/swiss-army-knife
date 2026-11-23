package io.pelle.sak.backend.users

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.pelle.sak.core.Language
import io.pelle.sak.auth.LoginApp
import io.pelle.sak.core.MailSender
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AppLinkServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val users by lazy {
        UserService(SakDatabase.open(tempDir.resolve("sak.db")), admins = listOf("admin@example.org")).apply {
            create("admin@example.org", "anna@example.org", "Anna")
        }
    }

    private val mails = mutableListOf<Pair<String, String>>()

    private fun service(sender: MailSender? = MailSender { to, _, text -> mails += to to text }) =
        AppLinkService(users, sender?.let { AppLinkMailer(it, LoginApp("https://sak.example.org/", "SAK")) })

    @Test
    fun `a user and an admin get the links of the apps, by their normalized address`() {
        service().send(" Anna@Example.org ", Language.EN) shouldBe AppLinkSendResult.Sent
        service().send("admin@example.org", Language.EN) shouldBe AppLinkSendResult.Sent

        mails.map { it.first } shouldBe listOf("anna@example.org", "admin@example.org")
        mails.first().second shouldContain "https://sak.example.org$TODO_APP_DOWNLOAD_PATH"
    }

    @Test
    fun `no mail goes to an address the deployment does not know, without a mail server, or when the server refuses it`() {
        service().send("nobody@example.org", Language.EN) shouldBe AppLinkSendResult.NotAUser
        service(sender = null).send("anna@example.org", Language.EN) shouldBe AppLinkSendResult.NoMailServer
        service(MailSender { _, _, _ -> error("refused") }).send("anna@example.org", Language.EN) shouldBe AppLinkSendResult.NotSent

        mails shouldBe emptyList()
    }
}
