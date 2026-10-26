package io.pelle.sak.auth

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

fun interface MailSender {
    fun send(
        to: String,
        subject: String,
        text: String,
    )
}

class SmtpMailSender(
    host: String,
    port: Int,
    private val username: String?,
    private val password: String?,
    private val fromAddress: String,
    // implicit tls (smtps, usually port 465) instead of starttls
    tls: Boolean = false,
    // plain smtp is only meant for local test sinks
    starttls: Boolean = true,
    timeoutMillis: Int = 10_000,
) : MailSender {
    private val session =
        Session.getInstance(
            Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", (username != null).toString())
                put("mail.smtp.ssl.enable", tls.toString())
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.starttls.enable", (starttls && !tls).toString())
                put("mail.smtp.starttls.required", (starttls && !tls).toString())
                put("mail.smtp.connectiontimeout", timeoutMillis.toString())
                put("mail.smtp.timeout", timeoutMillis.toString())
                put("mail.smtp.writetimeout", timeoutMillis.toString())
            },
        )

    override fun send(
        to: String,
        subject: String,
        text: String,
    ) {
        val message =
            MimeMessage(session).apply {
                setFrom(InternetAddress(fromAddress))
                setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
                setSubject(subject, Charsets.UTF_8.name())
                setText(text, Charsets.UTF_8.name())
            }
        if (username != null) {
            Transport.send(message, username, password)
        } else {
            Transport.send(message)
        }
    }
}
