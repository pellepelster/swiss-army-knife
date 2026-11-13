package io.pelle.sak.auth

import io.pelle.sak.api.logClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.openapi.HttpSecurityScheme
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

const val API_SIGNATURE_AUTH = "api-signature"

// who acts with a signed request of sakctl: no e-mail address, what it creates is its own
const val API_IDENTITY = "sakctl"

// the scheme of the authorization header: `SAK-Ed25519 ts=<unix seconds>,nonce=<32 hex>,sig=<base64>`
const val SIGNATURE_SCHEME = "SAK-Ed25519"

// the security scheme of a signed request in the openapi document, named like its provider
val apiSignatureSecurityScheme =
    HttpSecurityScheme(
        scheme = SIGNATURE_SCHEME,
        description =
            "A request sakctl signs with the ed25519 key it derives from the password of the deployment, see sakctl " +
                "config show: the signature holds for the host, the method, the path, the time and a nonce of its own.",
    )

// the requests of sakctl, signed with the ed25519 key it derives from the deployment password; the
// backend knows only the public half (api_public_key), no request carries a secret. The signature
// holds for the host, the method, the path and query (without the prefix of caddy), the time and a
// nonce, so a replay, an old request (MAX_AGE_SECONDS) or one for another host is refused. No body:
// sakctl only reads
class ApiSignature(
    publicKey: String,
    private val host: String,
    private val pathPrefix: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val key =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_X509_PREFIX + Base64.getDecoder().decode(publicKey)))

    // the nonces of the signatures taken, with their time; one older than MAX_AGE_SECONDS goes
    private val seen = ConcurrentHashMap<String, Long>()

    // null when the request is signed with the key, else why it is refused
    fun refusal(
        method: String,
        uri: String,
        authorization: String,
        hasBody: Boolean,
    ): String? {
        val fields =
            authorization
                .removePrefix("$SIGNATURE_SCHEME ")
                .split(',')
                .mapNotNull { field -> field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                .toMap()
        val timestamp = fields["ts"]?.toLongOrNull()
        val nonce = fields["nonce"]
        val signature = fields["sig"]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val now = clock.instant().epochSecond
        return when {
            timestamp == null || nonce == null || signature == null -> "no ts, nonce or sig"
            !NONCE.matches(nonce) -> "the nonce is no 32 hex characters"
            hasBody -> "a signed request carries no body"
            !uri.startsWith("$pathPrefix/") -> "the path is not below $pathPrefix"
            kotlin.math.abs(now - timestamp) > MAX_AGE_SECONDS -> "the time is more than $MAX_AGE_SECONDS seconds away"
            !verifies(signedText(method, host, uri.removePrefix(pathPrefix), timestamp, nonce), signature) -> "the signature does not hold"
            seen.putIfAbsent(nonce, timestamp) != null -> "the nonce was seen before"
            else -> {
                seen.entries.removeIf { kotlin.math.abs(now - it.value) > MAX_AGE_SECONDS }
                null
            }
        }
    }

    private fun verifies(
        text: String,
        signature: ByteArray,
    ): Boolean =
        runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(text.toByteArray())
                verify(signature)
            }
        }.getOrDefault(false)

    companion object {
        const val MAX_AGE_SECONDS = 120L
        private val NONCE = Regex("[0-9a-f]{32}")

        // the der prefix of an ed25519 public key (rfc 8410) before its 32 bytes
        private val ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100")

        // must match signed_text in sak-cli/src-tauri/src/core/config.rs; the body hash is of an
        // empty body
        fun signedText(
            method: String,
            host: String,
            pathAndQuery: String,
            timestamp: Long,
            nonce: String,
        ): String {
            val body = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ByteArray(0)))
            return "sak-api-v1\n$method\n${host.lowercase()}\n$pathAndQuery\n$timestamp\n$nonce\n$body"
        }
    }
}

private class ApiSignatureProvider(
    config: Config,
) : AuthenticationProvider(config) {
    private val signature = config.signature
    private val owner = config.owner

    class Config(
        name: String,
        val signature: ApiSignature?,
        // the user sakctl acts as, its data is theirs
        val owner: String,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val authorization = call.request.headers[HttpHeaders.Authorization]
        // a session token is for the other provider, it is no failed signature
        if (authorization == null || !authorization.startsWith("$SIGNATURE_SCHEME ")) {
            context.error(API_SIGNATURE_AUTH, AuthenticationFailedCause.NoCredentials)
            return
        }
        val refusal =
            if (signature == null) {
                "no api_public_key is configured"
            } else {
                signature.refusal(call.request.local.method.value, call.request.local.uri, authorization, call.hasBody())
            }
        if (refusal == null) {
            context.principal(name, SessionPrincipal(API_IDENTITY, null, setOf(Role.ADMIN), owner = owner))
        } else {
            // the client masked, never the header
            logger.info { "refused a signed request to ${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')} from ${logClient(call.clientAddress())}: $refusal" }
            context.error(API_SIGNATURE_AUTH, AuthenticationFailedCause.InvalidCredentials)
        }
    }
}

private fun ApplicationCall.hasBody() =
    (request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > 0 || request.headers[HttpHeaders.TransferEncoding] != null


// a signed request of sakctl is an admin that never expires, acting as owner (the admin of the
// password login); without a public key every signed request is refused
fun AuthenticationConfig.apiSignature(
    signature: ApiSignature?,
    owner: String,
) {
    register(ApiSignatureProvider(ApiSignatureProvider.Config(API_SIGNATURE_AUTH, signature, owner)))
}
