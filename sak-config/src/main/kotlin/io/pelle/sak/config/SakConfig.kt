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
    // the console and its e-mail login are only served when configured
    val auth: AuthConfig? = null,
    val logs: LogsConfig = LogsConfig(),
) {
    val s3DataDir: Path get() = dataDir.resolve("s3")

    val authDataDir: Path get() = dataDir.resolve("auth")

    val consoleHost: String get() = "console.$rootDomain"

    // the login links in the e-mails point here
    val consoleUrl: String get() = auth?.consoleUrl ?: "https://$consoleHost"

    // sak-frontend, its apps and their login
    val appsHost: String get() = "apps.$rootDomain"

    // the login links of sak-frontend point here
    val frontendUrl: String get() = auth?.frontendUrl ?: "https://$appsHost"

    @Serializable
    data class LogsConfig(
        // the ingress access logs end up here, the console shows the metrics of the sites from it
        @SerialName("victorialogs_url")
        val victorialogsUrl: String = DEFAULT_VICTORIALOGS_URL,
    )

    @Serializable
    data class AuthConfig(
        // only these addresses get a login link, compared case-insensitively
        // the addresses that may sign in to the console, as admins
        val admins: List<String> = emptyList(),
        val smtp: SmtpConfig,
        // overrides https://console.<root_domain>, e.g. for local tests
        @SerialName("console_url")
        val consoleUrl: String? = null,
        // overrides https://apps.<root_domain>, e.g. for local tests
        @SerialName("frontend_url")
        val frontendUrl: String? = null,
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

    companion object {
        const val DEFAULT_CADDY_ADMIN_URL = "http://localhost:2019"

        const val DEFAULT_VICTORIALOGS_URL = "http://127.0.0.1:9428"
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
