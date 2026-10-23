package io.pelle.sak.s3.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonRootName
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import tools.jackson.dataformat.xml.XmlMapper
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import tools.jackson.module.kotlin.kotlinModule

const val S3_XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/"

object S3Xml {
    private val mapper: XmlMapper =
        XmlMapper
            .builder()
            .defaultUseWrapper(false)
            .addModule(kotlinModule())
            .build()

    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun toXml(value: Any): String = """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + mapper.writeValueAsString(value)

    fun <T> fromXml(
        xml: String,
        type: Class<T>,
    ): T = mapper.readValue(xml, type)

    fun timestamp(instant: Instant): String = TIMESTAMP_FORMAT.format(instant)

    fun quote(etag: String) = "\"$etag\""
}

@JsonRootName(value = "ListBucketResult", namespace = S3_XMLNS)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ListBucketResult(
    @field:JsonProperty("Name") val name: String,
    @field:JsonProperty("Prefix") val prefix: String,
    @field:JsonProperty("Delimiter") val delimiter: String?,
    @field:JsonProperty("StartAfter") val startAfter: String?,
    @field:JsonProperty("ContinuationToken") val continuationToken: String?,
    @field:JsonProperty("NextContinuationToken") val nextContinuationToken: String?,
    @field:JsonProperty("KeyCount") val keyCount: Int,
    @field:JsonProperty("MaxKeys") val maxKeys: Int,
    @field:JsonProperty("IsTruncated") val isTruncated: Boolean,
    @field:JacksonXmlElementWrapper(useWrapping = false)
    @field:JsonProperty("Contents")
    val contents: List<Contents>,
    @field:JacksonXmlElementWrapper(useWrapping = false)
    @field:JsonProperty("CommonPrefixes")
    val commonPrefixes: List<CommonPrefix>,
) {
    data class Contents(
        @field:JsonProperty("Key") val key: String,
        @field:JsonProperty("LastModified") val lastModified: String,
        @field:JsonProperty("ETag") val etag: String,
        @field:JsonProperty("Size") val size: Long,
        @field:JsonProperty("StorageClass") val storageClass: String = "STANDARD",
    )

    data class CommonPrefix(
        @field:JsonProperty("Prefix") val prefix: String,
    )
}

@JsonRootName(value = "InitiateMultipartUploadResult", namespace = S3_XMLNS)
data class InitiateMultipartUploadResult(
    @field:JsonProperty("Bucket") val bucket: String,
    @field:JsonProperty("Key") val key: String,
    @field:JsonProperty("UploadId") val uploadId: String,
)

@JsonRootName(value = "CompleteMultipartUploadResult", namespace = S3_XMLNS)
data class CompleteMultipartUploadResult(
    @field:JsonProperty("Location") val location: String,
    @field:JsonProperty("Bucket") val bucket: String,
    @field:JsonProperty("Key") val key: String,
    @field:JsonProperty("ETag") val etag: String,
)

@JsonRootName(value = "CompleteMultipartUpload")
data class CompleteMultipartUploadRequest(
    @field:JacksonXmlElementWrapper(useWrapping = false)
    @field:JsonProperty("Part")
    val parts: List<Part> = emptyList(),
) {
    data class Part(
        @field:JsonProperty("PartNumber") val partNumber: Int = 0,
        @field:JsonProperty("ETag") val etag: String = "",
    )
}
