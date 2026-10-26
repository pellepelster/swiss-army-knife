package io.pelle.sak.auth

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SmtpMailSenderTest {
    @RegisterExtension
    val greenMail: GreenMailExtension = GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())

    @Test
    fun `sends plain text mails through smtp`() {
        val sender =
            SmtpMailSender(
                host = "localhost",
                port = greenMail.smtp.port,
                username = null,
                password = null,
                fromAddress = "login@example.org",
                starttls = false,
            )

        sender.send("admin@example.org", "Sign in to the console", "https://console.example.org/signin?token=a.b.c")

        greenMail.waitForIncomingEmail(5_000, 1) shouldBe true
        val message = greenMail.receivedMessages.single()
        message.allRecipients.single().toString() shouldBe "admin@example.org"
        message.from.single().toString() shouldBe "login@example.org"
        message.subject shouldBe "Sign in to the console"
        GreenMailUtil.getBody(message) shouldContain "https://console.example.org/signin?token=a.b.c"
    }

    @Test
    fun `authenticates when credentials are configured`() {
        greenMail.setUser("login@example.org", "login", "secret")
        val sender =
            SmtpMailSender(
                host = "localhost",
                port = greenMail.smtp.port,
                username = "login",
                password = "secret",
                fromAddress = "login@example.org",
                starttls = false,
            )

        sender.send("admin@example.org", "subject", "text")

        greenMail.waitForIncomingEmail(5_000, 1) shouldBe true
    }
}
