package io.pelle.sak.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class AdminPasswordTest {
    @Test
    fun `checks a password against the hash sakctl writes`() {
        // the vector of sakctl (ADMIN_PASSWORD_VECTOR in sak-cli/src-tauri/src/core/keys.rs)
        val password = AdminPassword(VECTOR)

        password.matches("a password of sixteen") shouldBe true
        password.matches("a password of sixteeN") shouldBe false
        password.matches("") shouldBe false
    }

    @Test
    fun `refuses what is no argon2id hash`() {
        listOf(
            "plain",
            "\$argon2i\$v=19\$m=19456,t=2,p=1\$qEK767nINg0KkEtAqKeQDg\$o63nEWUsl16I1CzzLIWT2j8a1N9p0Nzhyf0iXOhdZK8",
            "\$argon2id\$v=16\$m=19456,t=2,p=1\$qEK767nINg0KkEtAqKeQDg\$o63nEWUsl16I1CzzLIWT2j8a1N9p0Nzhyf0iXOhdZK8",
            "\$argon2id\$v=19\$t=2,p=1\$qEK767nINg0KkEtAqKeQDg\$o63nEWUsl16I1CzzLIWT2j8a1N9p0Nzhyf0iXOhdZK8",
        ).forEach { shouldThrow<IllegalArgumentException> { AdminPassword(it) } }
    }

    @Test
    fun `never shows the hash`() {
        AdminPassword(VECTOR).toString() shouldNotContain "o63nEW"
    }

    companion object {
        const val VECTOR = "\$argon2id\$v=19\$m=19456,t=2,p=1\$qEK767nINg0KkEtAqKeQDg\$o63nEWUsl16I1CzzLIWT2j8a1N9p0Nzhyf0iXOhdZK8"
    }
}
