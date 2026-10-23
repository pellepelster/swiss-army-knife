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
) {
    val s3DataDir: Path get() = s3.dataDir ?: dataDir.resolve("s3")

    @Serializable
    data class S3Config(
        @SerialName("access_key_id")
        val accessKeyId: String,
        @SerialName("secret_access_key")
        val secretAccessKey: String,
        @SerialName("data_dir")
        @Serializable(with = PathSerializer::class)
        val dataDir: Path? = null,
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
