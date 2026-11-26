package io.pelle.sak.s3.auth

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CRC32C

// the checksum of the whole object a streamed upload carries in its trailer (SEC-23), by the name of
// its header (x-amz-trailer), e.g. x-amz-checksum-crc32; its value is the base64 of its bytes
internal sealed interface TrailerChecksum {
    fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    fun value(): ByteArray

    companion object {
        // null for a header that is no checksum of S3
        fun of(header: String): TrailerChecksum? =
            when (header.lowercase()) {
                "x-amz-checksum-crc32" -> Jdk(CRC32())
                "x-amz-checksum-crc32c" -> Jdk(CRC32C())
                "x-amz-checksum-crc64nvme" -> Crc64Nvme()
                "x-amz-checksum-sha1" -> Digest(MessageDigest.getInstance("SHA-1"))
                "x-amz-checksum-sha256" -> Digest(MessageDigest.getInstance("SHA-256"))
                else -> null
            }
    }

    private class Jdk(
        private val checksum: java.util.zip.Checksum,
    ) : TrailerChecksum {
        override fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = checksum.update(bytes, offset, length)

        // 32 bits, big endian
        override fun value(): ByteArray = ByteBuffer.allocate(4).putInt(checksum.value.toInt()).array()
    }

    private class Digest(
        private val digest: MessageDigest,
    ) : TrailerChecksum {
        override fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = digest.update(bytes, offset, length)

        override fun value(): ByteArray = digest.digest()
    }

    // CRC-64/NVME: reflected, polynomial 0x9a6c9329ac4bc9b5, all ones at the start and at the end
    private class Crc64Nvme : TrailerChecksum {
        private var crc = -1L

        override fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            for (i in offset until offset + length) {
                crc = TABLE[((crc xor bytes[i].toLong()) and 0xff).toInt()] xor (crc ushr 8)
            }
        }

        override fun value(): ByteArray = ByteBuffer.allocate(8).putLong(crc.inv()).array()

        companion object {
            private const val POLYNOMIAL = -0x6593_6cd6_53b4_364bL // 0x9a6c9329ac4bc9b5

            private val TABLE =
                LongArray(256) { index ->
                    (0 until 8).fold(index.toLong()) { value, _ -> if (value and 1L != 0L) (value ushr 1) xor POLYNOMIAL else value ushr 1 }
                }
        }
    }
}
