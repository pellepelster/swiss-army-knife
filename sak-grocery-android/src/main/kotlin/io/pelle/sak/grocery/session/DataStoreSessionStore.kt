package io.pelle.sak.grocery.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "session")

private val API_URL = stringPreferencesKey("api_url")
private val DEPLOYMENT = stringPreferencesKey("deployment")
private val TOKEN = stringPreferencesKey("token")
private val EMAIL = stringPreferencesKey("email")
private val EXPIRES_AT = longPreferencesKey("expires_at")
private val PENDING_API_URL = stringPreferencesKey("pending_api_url")
private val PENDING_STARTED_AT = longPreferencesKey("pending_started_at")

// the preferences of the app, the session token encrypted with a key of the android keystore that
// never leaves it
class DataStoreSessionStore(
    private val context: Context,
) : SessionStore {
    override suspend fun load(): Stored {
        val preferences = context.dataStore.data.first()
        return Stored(
            apiUrl = preferences[API_URL],
            deployment = preferences[DEPLOYMENT],
            // a token the key can not open any more (e.g. the app was restored elsewhere) is none
            token = preferences[TOKEN]?.let { runCatching { KeystoreCipher.decrypt(it) }.getOrNull() },
            email = preferences[EMAIL],
            expiresAt = preferences[EXPIRES_AT]?.let(Instant::ofEpochMilli),
            pending =
                preferences[PENDING_API_URL]?.let { url ->
                    preferences[PENDING_STARTED_AT]?.let { PendingLogin(url, Instant.ofEpochMilli(it)) }
                },
        )
    }

    override suspend fun save(stored: Stored) {
        context.dataStore.edit { preferences ->
            preferences.clear()
            stored.apiUrl?.let { preferences[API_URL] = it }
            stored.deployment?.let { preferences[DEPLOYMENT] = it }
            stored.token?.let { preferences[TOKEN] = KeystoreCipher.encrypt(it) }
            stored.email?.let { preferences[EMAIL] = it }
            stored.expiresAt?.let { preferences[EXPIRES_AT] = it.toEpochMilli() }
            stored.pending?.let {
                preferences[PENDING_API_URL] = it.apiUrl
                preferences[PENDING_STARTED_AT] = it.startedAt.toEpochMilli()
            }
        }
    }
}

// aes-gcm with a key of the android keystore; a value is its iv and the cipher text, base64
private object KeystoreCipher {
    private const val ALIAS = "sak-grocery-session"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(encrypted: String): String {
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }
}
