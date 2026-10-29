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
    fun `takes the password however its umlauts were typed`() {
        // the hash sakctl writes for the password as nfc
        val password = AdminPassword(UMLAUT_VECTOR)
        password.matches("Gr\u00fc\u00dfe aus dem S\u00fcden, sechzehn") shouldBe true
        // u and the combining diaeresis, as macos may type it
        password.matches("Gru\u0308\u00dfe aus dem Su\u0308den, sechzehn") shouldBe true
        password.matches("Grusse aus dem Suden, sechzehn") shouldBe false
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
        AdminPassword(VECTOR).toString() shouldNotContain "L7n0a0d"
    }

    companion object {
        const val UMLAUT_VECTOR = "\$argon2id\$v=19\$m=65536,t=3,p=1\$al/AGZtQJD5CTnS21yvIXg\$SpIVgiuAa4G3pa2GALp3BEwmy4mqb2K4iHx7SxgijdA"
        const val VECTOR = "\$argon2id\$v=19\$m=65536,t=3,p=1\$al/AGZtQJD5CTnS21yvIXg\$L7n0a0d/Gu4awCZm7tuz99SWhVvUedmj0UABpTBu6l4"
    }
}
