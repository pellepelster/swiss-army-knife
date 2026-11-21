package io.pelle.sak.model

import java.security.SecureRandom

// the name of the data of a row outside the database, e.g. the directory of a stored file or of a repository of restic
// (MODEL-8): 12 random characters a-z0-9, set once when the row is inserted and never given to another
// row of its table, also not after the row is deleted. It is no secret and no PII
@JvmInline
value class ResourceId(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "no resource id" }
    }

    override fun toString() = value

    companion object {
        const val LENGTH = 12

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        private val PATTERN = Regex("^[a-z0-9]{$LENGTH}$")

        fun random(random: SecureRandom) = ResourceId(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

        // the resource id of `value`, null when it is none
        fun of(value: String): ResourceId? = if (PATTERN.matches(value)) ResourceId(value) else null
    }
}
