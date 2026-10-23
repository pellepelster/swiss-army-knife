package io.pelle.sak.s3.auth

import io.pelle.sak.s3.S3AccessCallback
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SigV4Request(
    val method: String,
    val rawUri: String,
    val rawQuery: String?,
    val headers: Map<String, List<String>>,
    val bucket: String? = null,
) {
    fun header(name: String) = headers[name]?.joinToString(",")
}

sealed class SigV4Result {
    data class Authenticated(
        val accessKeyId: String,
    ) : SigV4Result()

    data class Rejected(
        val status: Int,
        val code: String,
        val message: String,
    ) : SigV4Result()
}

class SigV4Verifier(
    private val accessCallback: S3AccessCallback,
    private val maxClockSkew: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(request: SigV4Request): SigV4Result {
        if (request.rawQuery?.contains("X-Amz-Signature=") == true) {
            return SigV4Result.Rejected(501, "NotImplemented", "presigned URLs are not supported")
        }

        val authorization =
            request.header("authorization")
                ?: return SigV4Result.Rejected(403, "AccessDenied", "missing Authorization header")
        if (!authorization.startsWith(ALGORITHM)) {
            return SigV4Result.Rejected(403, "AccessDenied", "unsupported authorization scheme")
        }

        val parsed =
            parseAuthorization(authorization)
                ?: return SigV4Result.Rejected(400, "InvalidRequest", "malformed Authorization header")

        val secretKey =
            accessCallback.resolveSecretKey(parsed.accessKeyId, request.bucket)
                ?: return SigV4Result.Rejected(403, "InvalidAccessKeyId", "unknown access key id")

        val amzDate =
            request.header("x-amz-date")
                ?: return SigV4Result.Rejected(400, "InvalidRequest", "missing x-amz-date header")
        val requestTime =
            try {
                LocalDateTime.parse(amzDate, AMZ_DATE_FORMAT).toInstant(ZoneOffset.UTC)
            } catch (e: DateTimeParseException) {
                return SigV4Result.Rejected(400, "InvalidRequest", "malformed x-amz-date header")
            }
        if (Duration.between(requestTime, clock.instant()).abs() > maxClockSkew) {
            return SigV4Result.Rejected(403, "RequestTimeTooSkewed", "request time differs too much from server time")
        }

        val payloadHash = request.header("x-amz-content-sha256") ?: EMPTY_BODY_SHA256

        val canonicalRequest =
            buildString {
                append(request.method.uppercase()).append('\n')
                append(canonicalUri(request.rawUri)).append('\n')
                append(canonicalQuery(request.rawQuery)).append('\n')
                parsed.signedHeaders.forEach { name ->
                    val value = request.header(name) ?: return SigV4Result.Rejected(400, "InvalidRequest", "signed header '$name' missing")
                    append(name).append(':').append(value.trim().replace(WHITESPACE, " ")).append('\n')
                }
                append('\n')
                append(parsed.signedHeaders.joinToString(";")).append('\n')
                append(payloadHash)
            }

        val stringToSign =
            buildString {
                append(ALGORITHM).append('\n')
                append(amzDate).append('\n')
                append(parsed.scope).append('\n')
                append(sha256Hex(canonicalRequest))
            }

        val signingKey =
            parsed.scopeParts.fold("AWS4$secretKey".toByteArray()) { key, part -> hmacSha256(key, part) }
        val signature = hmacSha256(signingKey, stringToSign).toHex()

        if (!MessageDigest.isEqual(signature.toByteArray(), parsed.signature.toByteArray())) {
            return SigV4Result.Rejected(403, "SignatureDoesNotMatch", "request signature does not match")
        }

        return SigV4Result.Authenticated(parsed.accessKeyId)
    }

    private data class ParsedAuthorization(
        val accessKeyId: String,
        val scopeParts: List<String>,
        val signedHeaders: List<String>,
        val signature: String,
    ) {
        val scope = scopeParts.joinToString("/")
    }

    private fun parseAuthorization(header: String): ParsedAuthorization? {
        val elements =
            header
                .removePrefix(ALGORITHM)
                .split(',')
                .mapNotNull {
                    val parts = it.trim().split('=', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()

        val credential = elements["Credential"]?.split('/') ?: return null
        if (credential.size != 5 || credential[4] != "aws4_request") return null
        val signedHeaders = elements["SignedHeaders"]?.split(';')?.filter { it.isNotEmpty() } ?: return null
        val signature = elements["Signature"] ?: return null

        return ParsedAuthorization(
            accessKeyId = credential[0],
            scopeParts = credential.drop(1),
            signedHeaders = signedHeaders.map { it.lowercase() }.sorted(),
            signature = signature,
        )
    }

    private fun canonicalUri(rawUri: String): String =
        rawUri
            .split('/')
            .joinToString("/") { uriEncode(percentDecode(it), encodeSlash = true) }
            .ifEmpty { "/" }

    private fun canonicalQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        return rawQuery
            .split('&')
            .filter { it.isNotEmpty() }
            .map {
                val index = it.indexOf('=')
                if (index >= 0) it.substring(0, index) to it.substring(index + 1) else it to ""
            }.map { (name, value) ->
                uriEncode(percentDecode(name), encodeSlash = true) to
                    uriEncode(percentDecode(value), encodeSlash = true)
            }.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }
    }

    private fun percentDecode(value: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val decoded = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (decoded != null) {
                    out.write(decoded)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray())
            i++
        }
        return String(out.toByteArray())
    }

    private fun uriEncode(
        value: String,
        encodeSlash: Boolean,
    ): String =
        buildString {
            value.toByteArray().forEach { byte ->
                val c = byte.toInt().toChar()
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~" -> append(c)
                    c == '/' && !encodeSlash -> append(c)
                    else -> append("%%%02X".format(byte.toInt() and 0xff))
                }
            }
        }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    private fun hmacSha256(
        key: ByteArray,
        value: String,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray())
        }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"

        private const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        private val AMZ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        private val WHITESPACE = Regex("\\s+")

        val STREAMING_PAYLOAD_MODES =
            setOf(
                "STREAMING-UNSIGNED-PAYLOAD-TRAILER",
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD",
                "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER",
            )
    }
}
