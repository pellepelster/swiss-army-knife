package io.pelle.sak.api

import java.net.InetAddress
import java.security.MessageDigest

// enough to tell the users of a deployment apart
private const val LOG_ADDRESS_DIGITS = 12

// an e-mail address as the logs name it: the start of the sha-256 of the trimmed, lower case
// address, never the address itself (personal data, the logs leave the server). Stable on every
// deployment, so the lines of a user are found with `printf '%s' 'anna@example.org' | sha256sum`
fun logAddress(email: String): String =
    "user:" +
        MessageDigest
            .getInstance("SHA-256")
            .digest(email.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(LOG_ADDRESS_DIGITS)

// the address of a client as the logs name it: its network only, /24 of ipv4 and /64 of ipv6 like
// caddy masks it (ip_mask 24 64); what is no ip literal is left out
fun logClient(address: String): String {
    val ip = runCatching { InetAddress.ofLiteral(address.trim().removePrefix("[").removeSuffix("]")) }.getOrNull() ?: return "client:unknown"
    val host = ip.hostAddress.substringBefore('%')
    // hostAddress writes every group of ipv6, uncompressed
    return if (ip.address.size == 4) host.substringBeforeLast('.') + ".0" else host.split(':').take(4).joinToString(":") + "::"
}

private val EMAIL = Regex("""[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""")

private val IPV4 = Regex("""\b(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}\b""")

// a text for the logs with every e-mail address as logAddress and every ipv4 address as its network,
// e.g. the message of an exception of the mail server that names the recipient
fun logText(text: String): String = text.replace(EMAIL) { logAddress(it.value) }.replace(IPV4) { "${it.groupValues[1]}.0" }

// e for the logs: its class, stack trace and causes as they are, every message through logText. A
// logged exception prints its message and those of its causes, which may name a user
fun logCause(e: Throwable): Throwable = LoggedThrowable(e)

private class LoggedThrowable(
    original: Throwable,
) : Throwable(original.message?.let(::logText), original.cause?.let(::LoggedThrowable), false, true) {
    private val type = original.javaClass.name

    init {
        stackTrace = original.stackTrace
        original.suppressed.forEach { addSuppressed(LoggedThrowable(it)) }
    }

    override fun toString(): String = message?.let { "$type: $it" } ?: type
}
