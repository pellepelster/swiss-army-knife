package io.pelle.sak.backend.secrets

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.secrets.SecretRepository
import io.pelle.sak.model.secrets.SecretRow
import java.time.Clock

private val logger = KotlinLogging.logger {}

// every secret of the controller, the only way to them: stored encrypted (SecretCipher), handed out
// decrypted as a Secret; the type and scope are authenticated with the value. Secrets under an older
// key are encrypted under the newest one when the controller starts, so a key is rotated by adding
// a new version to sak.secret_keys and removed once everything was started with it
class SecretService(
    private val repository: SecretRepository,
    private val cipher: SecretCipher,
) {
    constructor(
        database: SakDatabase,
        keys: Map<Int, String>,
        rootDomain: String,
        clock: Clock = Clock.systemUTC(),
    ) : this(SecretRepository(database, clock), SecretCipher(keys, rootDomain))

    fun get(
        type: SecretType,
        scope: String = "",
    ): Secret? = repository.byTypeAndScope(type.name, scope)?.let { Secret(decrypt(it)) }

    // false when the type and scope have a secret already
    fun put(
        type: SecretType,
        scope: String,
        value: String,
    ): Boolean {
        val encrypted = cipher.encrypt(value, associatedData(type.name, scope, cipher.activeVersion))
        return repository.insert(type.name, scope, encrypted.keyVersion, encrypted.nonce, encrypted.ciphertext) != null
    }

    // the secret, created once when there is none
    fun getOrCreate(
        type: SecretType,
        scope: String = "",
        create: () -> String,
    ): Secret {
        get(type, scope)?.let { return it }
        put(type, scope, create())
        // another one may have been put meanwhile, the stored one counts
        return get(type, scope) ?: error("the secret $type of '$scope' was put but is not there")
    }

    // it stays as deleted; false when there was none
    fun delete(
        type: SecretType,
        scope: String,
    ): Boolean = repository.delete(type.name, scope)

    // the secrets under an older key encrypted under the newest one, each on its own; the ones that
    // fail are logged and stay as they are
    fun reencryptUnderNewestKey(): Int {
        val reencrypted =
            repository.belowKeyVersion(cipher.activeVersion).count { row ->
                try {
                    val encrypted = cipher.encrypt(decrypt(row), associatedData(row.type, row.scope, cipher.activeVersion))
                    repository.reencrypt(row.id, encrypted.keyVersion, encrypted.nonce, encrypted.ciphertext)
                } catch (e: SecretKeyException) {
                    logger.error(e) { "could not encrypt the secret ${row.type} of '${row.scope}' under key ${cipher.activeVersion}" }
                    false
                }
            }
        if (reencrypted > 0) logger.info { "encrypted $reencrypted secrets under key ${cipher.activeVersion}" }
        return reencrypted
    }

    private fun decrypt(row: SecretRow) =
        cipher.decrypt(Encrypted(row.keyVersion, row.nonce, row.ciphertext), associatedData(row.type, row.scope, row.keyVersion))

    // what a value is for, it decrypts for nothing else
    private fun associatedData(
        type: String,
        scope: String,
        keyVersion: Int,
    ) = "$type|$scope|$keyVersion"
}
