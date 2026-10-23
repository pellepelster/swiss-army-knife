package io.pelle.sak.s3.api

import java.util.UUID

object S3ErrorXml {
    fun body(
        code: String,
        message: String,
        resource: String? = null,
    ): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("<Error>")
            append("<Code>").append(escape(code)).append("</Code>")
            append("<Message>").append(escape(message)).append("</Message>")
            if (resource != null) {
                append("<Resource>").append(escape(resource)).append("</Resource>")
            }
            append("<RequestId>").append(UUID.randomUUID().toString()).append("</RequestId>")
            append("</Error>")
        }

    private fun escape(value: String) =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
