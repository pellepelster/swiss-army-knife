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
    // sak-frontend and its e-mail login are only served when configured
    val auth: AuthConfig? = null,
    val logs: LogsConfig = LogsConfig(),
    // the text discovery in the photos and recordings of notes is only offered when configured
    val openrouter: OpenRouterConfig? = null,
    // the keys the secrets are encrypted with (the secrets table) by their version, the highest
    // encrypts; each is at least 32 random bytes, base64. The backend refuses to start without one
    @SerialName("secret_keys")
    val secretKeys: Map<Int, String> = emptyMap(),
) {
    // the secret keys never show, e.g. in a log line
    override fun toString() =
        "SakConfig(dataDir=$dataDir, rootDomain=$rootDomain, caddyAdminUrl=$caddyAdminUrl, auth=$auth, logs=$logs, " +
            "openrouter=$openrouter, secretKeys=versions ${secretKeys.keys.sorted()})"

    val s3DataDir: Path get() = dataDir.resolve("s3")

    // sak-frontend, its apps and their login
    val appsHost: String get() = "apps.$rootDomain"

    // the api of sak-frontend below /v1 and the s3 api of the buckets
    val apiHost: String get() = "api.$rootDomain"

    // the login links of sak-frontend point here
    val frontendUrl: String get() = auth?.frontendUrl ?: "https://$appsHost"

    @Serializable
    data class LogsConfig(
        // the ingress access logs end up here, the access logs of the buckets are read from it
        @SerialName("victorialogs_url")
        val victorialogsUrl: String = DEFAULT_VICTORIALOGS_URL,
    )

    @Serializable
    data class AuthConfig(
        // the addresses that may sign in to sak-frontend, as admins, compared case-insensitively; no
        // other address gets a login link
        val admins: List<String> = emptyList(),
        val smtp: SmtpConfig,
        // overrides https://apps.<root_domain>, e.g. for local tests
        @SerialName("frontend_url")
        val frontendUrl: String? = null,
    )

    @Serializable
    data class OpenRouterConfig(
        @SerialName("api_token")
        val apiToken: String,
        // a vision model that reads the text in photos, its id at openrouter
        val model: String = DEFAULT_OPENROUTER_MODEL,
        // a speech to text model that writes down recordings, its id at openrouter
        @SerialName("speech_model")
        val speechModel: String = DEFAULT_OPENROUTER_SPEECH_MODEL,
        val url: String = DEFAULT_OPENROUTER_URL,
    ) {
        // the token never shows, e.g. in a log line
        override fun toString() = "OpenRouterConfig(model=$model, speechModel=$speechModel, url=$url)"
    }

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
    ) {
        // the password never shows, e.g. in a log line
        override fun toString() = "SmtpConfig(host=$host, port=$port, username=$username, from=$from, tls=$tls, starttls=$starttls)"
    }

    companion object {
        const val DEFAULT_CADDY_ADMIN_URL = "http://localhost:2019"

        const val DEFAULT_VICTORIALOGS_URL = "http://127.0.0.1:9428"

        const val DEFAULT_OPENROUTER_MODEL = "anthropic/claude-haiku-5.5"

        const val DEFAULT_OPENROUTER_SPEECH_MODEL = "openai/whisper-large-v3-turbo"

        const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1"
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
