package io.pelle.sak.backend.files

// the types the storage takes, told by their first bytes: images and wav recordings
internal fun contentType(head: ByteArray): String? {
    fun startsWith(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
    fun ascii(
        offset: Int,
        text: String,
    ) = head.size >= offset + text.length && text.indices.all { head[offset + it] == text[it].code.toByte() }
    return when {
        startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
        else -> null
    }
}
