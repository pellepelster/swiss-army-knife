package io.pelle.sak.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Path

@Serializable
data class SakConfig(
    // the sak name of the deployment the backend belongs to, written by the provisioning of the
    // server; GET /api/v1/info answers it
    val name: String = DEFAULT_NAME,
    // the dns mode of the deployment, written by the provisioning of the server like the name;
    // subdomain unless told, as sakctl creates a deployment
    @SerialName("dns_mode")
    val dnsMode: DnsMode = DnsMode.SUBDOMAIN,
    // the sha-256 (hex) of the admin api token sakctl derives from the password of the
    // deployment, written by the provisioning of the server; the token itself is nowhere on the
    // server. Without it, the api takes sessions only
    @SerialName("api_token_sha256")
    val apiTokenSha256: String? = null,
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
    // the public addresses of the server the backend runs on, none e.g. locally
    val addresses: AddressesConfig = AddressesConfig(),
    val dns: DnsConfig = DnsConfig(),
) {
    init {
        require(apiTokenSha256 == null || SHA256_HEX.matches(apiTokenSha256)) { "the api_token_sha256 is no sha-256 in hex" }
        require(SAK_NAME.matches(name)) {
            "the name '$name' is no sak name: 1 to 63 lower case letters, digits and dashes, starting and ending with a letter or digit"
        }
    }

    // the secret keys never show, e.g. in a log line
    override fun toString() =
        "SakConfig(name=$name, dnsMode=$dnsMode, dataDir=$dataDir, rootDomain=$rootDomain, caddyAdminUrl=$caddyAdminUrl, auth=$auth, logs=$logs, " +
            "openrouter=$openrouter, secretKeys=versions ${secretKeys.keys.sorted()}, addresses=$addresses, dns=$dns)"

    val s3DataDir: Path get() = dataDir.resolve("s3")

    // the domain the names of the deployment are below, by its dns mode: the root domain itself
    // (DOMAIN), or <name>.<root_domain> (SUBDOMAIN)
    val baseDomain: String get() =
        when (dnsMode) {
            DnsMode.DOMAIN -> rootDomain
            DnsMode.SUBDOMAIN -> "$name.$rootDomain"
        }

    // sak-frontend, its apps and their login: sak.<base_domain>
    val frontendHost: String get() = "$FRONTEND_LABEL.$baseDomain"

    // the api of sak-frontend below /v1 and the s3 api of the buckets: api.<base_domain>
    val apiHost: String get() = "$API_LABEL.$baseDomain"

    // the login links of sak-frontend point here
    val frontendUrl: String get() = auth?.frontendUrl ?: "https://$frontendHost"

    // each a comma separated list, the provisioning of the server writes them to backend.env;
    // every one must be an address of its family, the
    // backend refuses to start otherwise
    @Serializable
    data class AddressesConfig(
        @Serializable(with = Ipv4ListSerializer::class)
        val ipv4: List<String> = emptyList(),
        @Serializable(with = Ipv6ListSerializer::class)
        val ipv6: List<String> = emptyList(),
    )

    @Serializable
    data class DnsConfig(
        // the name servers the domains are checked with, by their address; each is asked
        // directly, a domain must point to the server at all of them
        val servers: List<String> = DEFAULT_DNS_SERVERS,
    ) {
        init {
            servers.forEach {
                require(runCatching { InetAddress.ofLiteral(it) }.isSuccess) { "the name server '$it' is no ip address" }
            }
        }
    }

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
        // overrides https://sak.<base_domain>, e.g. for local tests
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
        // the first labels of the hosts of sak-frontend and the api below the base domain
        const val FRONTEND_LABEL = "sak"
        const val API_LABEL = "api"

        // the name of a backend that is no deployment, e.g. the one of `mise run local:run`
        const val DEFAULT_NAME = "local"

        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        // what sakctl takes as a sak name (check_name in sak-cli/cloud): a hostname label
        private val SAK_NAME = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

        const val DEFAULT_CADDY_ADMIN_URL = "http://localhost:2019"

        const val DEFAULT_VICTORIALOGS_URL = "http://127.0.0.1:9428"

        const val DEFAULT_OPENROUTER_MODEL = "anthropic/claude-haiku-5.5"

        const val DEFAULT_OPENROUTER_SPEECH_MODEL = "openai/whisper-large-v3-turbo"

        const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1"

        // cloudflare, google and quad9, three operators that do not share a cache
        val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
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

// a comma separated list of ip addresses of one family, e.g. "203.0.113.1, 203.0.113.2"; blanks
// around the commas and empty entries are left out
abstract class AddressListSerializer(
    private val family: Class<out InetAddress>,
    // e.g. ipv4
    private val name: String,
) : KSerializer<List<String>> {
    override val descriptor = PrimitiveSerialDescriptor("io.pelle.sak.config.${name}List", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) = encoder.encodeString(value.joinToString(","))

    override fun deserialize(decoder: Decoder): List<String> =
        decoder.decodeString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.onEach {
            // a literal only, a host name would be looked up
            val address =
                try {
                    InetAddress.ofLiteral(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            require(family.isInstance(address)) { "'$it' is no $name address" }
        }
}

object Ipv4ListSerializer : AddressListSerializer(Inet4Address::class.java, "ipv4")

object Ipv6ListSerializer : AddressListSerializer(Inet6Address::class.java, "ipv6")
