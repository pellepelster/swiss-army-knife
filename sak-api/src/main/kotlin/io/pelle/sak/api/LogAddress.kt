package io.pelle.sak.api

import java.security.MessageDigest

// how many hex digits of the hash a log line shows, enough to tell the users of a deployment apart
private const val LOG_ADDRESS_DIGITS = 12

// an e-mail address as the logs name it: the first hex digits of the sha-256 of the address in lower
// case, never the address itself, which is personal data and leaves the server with the logs. The
// same address gives the same hash on every deployment and after every restart, so the lines of one
// user are found by hashing their address (`printf '%s' 'anna@example.org' | sha256sum`)
fun logAddress(email: String): String =
    "user:" +
        MessageDigest
            .getInstance("SHA-256")
            .digest(email.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(LOG_ADDRESS_DIGITS)
