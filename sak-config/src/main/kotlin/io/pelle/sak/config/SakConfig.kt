package io.pelle.sak.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Path

@Serializable
data class SakConfig(
    @SerialName("data_dir")
    @Serializable(with = PathSerializer::class)
    val dataDir: Path,
    @SerialName("root_domain")
    val rootDomain: String,
    @SerialName("caddy_admin_url")
    val caddyAdminUrl: String = DEFAULT_CADDY_ADMIN_URL,
    val redirects: Map<String, String> = emptyMap(),
    val s3: S3Config,
    // the console and its e-mail login are only served when configured
    val auth: AuthConfig? = null,
) {
    val s3DataDir: Path get() = dataDir.resolve("s3")

    val authDataDir: Path get() = dataDir.resolve("auth")

    val consoleHost: String get() = "console.$rootDomain"

    // the login links in the e-mails point here
    val consoleUrl: String get() = auth?.consoleUrl ?: "https://$consoleHost"

    @Serializable
    data class AuthConfig(
        // only these addresses get a login link, compared case-insensitively
        @SerialName("allowed_emails")
        val allowedEmails: List<String> = emptyList(),
        val smtp: SmtpConfig,
        // overrides https://console.<root_domain>, e.g. for local tests
        @SerialName("console_url")
        val consoleUrl: String? = null,
    )

    @Serializable
    data class SmtpConfig(
        val host: String,
        val port: Int = 587,
        val username: String? = null,
        val password: String? = null,
        val from: String,
        // implicit tls (smtps, usually port 465) instead of starttls
        val tls: Boolean = false,
        // plain smtp, only meant for local test sinks
        val starttls: Boolean = true,
    )

    @Serializable
    data class S3Config(
        @SerialName("access_key_id")
        val accessKeyId: String,
        @SerialName("secret_access_key")
        val secretAccessKey: String,
        val buckets: Map<String, S3BucketConfig> = emptyMap(),
    )

    @Serializable
    data class S3BucketConfig(
        val domains: List<String> = emptyList(),
    )

    companion object {
        const val DEFAULT_CADDY_ADMIN_URL = "http://localhost:2019"
    }
}

object PathSerializer : KSerializer<Path> {
    override val descriptor = PrimitiveSerialDescriptor("java.nio.file.Path", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Path,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Path = Path.of(decoder.decodeString())
}
