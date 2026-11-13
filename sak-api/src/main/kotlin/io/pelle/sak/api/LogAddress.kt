package io.pelle.sak.api

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
