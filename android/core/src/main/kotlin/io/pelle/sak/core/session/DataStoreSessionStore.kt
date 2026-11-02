package io.pelle.sak.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// a file that can not be read (e.g. cut off) is replaced by an empty one: the app starts on the
// deployment instead of failing on every start (AN-1)
private val Context.dataStore by preferencesDataStore(
    name = "session",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val API_URL = stringPreferencesKey("api_url")
private val ADDRESS = stringPreferencesKey("address")
private val TOKEN = stringPreferencesKey("token")
private val EMAIL = stringPreferencesKey("email")
private val EXPIRES_AT = longPreferencesKey("expires_at")
private val PENDING_API_URL = stringPreferencesKey("pending_api_url")
private val PENDING_STARTED_AT = longPreferencesKey("pending_started_at")
private val PENDING_NONCE = stringPreferencesKey("pending_nonce")

// the values of the app, each a key of its own with this prefix
private const val APP_PREFIX = "app."

// the preferences of the app, the session token and the nonce of a pending login encrypted with a key
// of the android keystore that never leaves it; app is the name of the app, e.g. grocery, the key is
// sak-<app>-session
class DataStoreSessionStore(
    private val context: Context,
    app: String,
) : SessionStore {
    private val cipher = KeystoreCipher("sak-$app-session")

    override suspend fun load(): Stored =
        read(
            context.dataStore.data
                .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
                .first(),
        )

    // read and written in one edit of the data store, which takes one edit at a time
    override suspend fun update(change: (Stored) -> Stored) {
        context.dataStore.edit { preferences -> write(preferences, change(read(preferences))) }
    }

    private fun read(preferences: Preferences) =
        Stored(
            apiUrl = preferences[API_URL],
            address = preferences[ADDRESS],
            // a token the key can not open any more (e.g. the app was restored elsewhere) is none
            token = preferences[TOKEN]?.let(cipher::decryptOrNull),
            email = preferences[EMAIL],
            expiresAt = preferences[EXPIRES_AT]?.let(Instant::ofEpochMilli),
            pending =
                preferences[PENDING_API_URL]?.let { url ->
                    val startedAt = preferences[PENDING_STARTED_AT] ?: return@let null
                    val nonce = preferences[PENDING_NONCE]?.let(cipher::decryptOrNull) ?: return@let null
                    PendingLogin(url, Instant.ofEpochMilli(startedAt), nonce)
                },
            app =
                preferences
                    .asMap()
                    .filterKeys { it.name.startsWith(APP_PREFIX) }
                    .mapNotNull { (key, value) -> (value as? String)?.let { key.name.removePrefix(APP_PREFIX) to it } }
                    .toMap(),
        )

    private fun write(
        preferences: MutablePreferences,
        stored: Stored,
    ) {
        preferences.clear()
        stored.apiUrl?.let { preferences[API_URL] = it }
        stored.address?.let { preferences[ADDRESS] = it }
        stored.token?.let { preferences[TOKEN] = cipher.encrypt(it) }
        stored.email?.let { preferences[EMAIL] = it }
        stored.expiresAt?.let { preferences[EXPIRES_AT] = it.toEpochMilli() }
        stored.pending?.let {
            preferences[PENDING_API_URL] = it.apiUrl
            preferences[PENDING_STARTED_AT] = it.startedAt.toEpochMilli()
            preferences[PENDING_NONCE] = cipher.encrypt(it.nonce)
        }
        stored.app.forEach { (name, value) -> preferences[stringPreferencesKey(APP_PREFIX + name)] = value }
    }
}

// aes-gcm with a key of the android keystore; a value is its iv and the cipher text, base64. A key
// that can no longer be used (some phones lose it with an update of android) is made anew; what it
// encrypted before is then unreadable and taken as none (AN-11)
private class KeystoreCipher(
    private val alias: String,
) {
    fun encrypt(plain: String): String =
        try {
            encryptWith(key(), plain)
        } catch (_: GeneralSecurityException) {
            encryptWith(newKey(), plain)
        }

    fun decryptOrNull(encrypted: String): String? =
        runCatching {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        }.getOrNull()

    private fun encryptWith(
        key: SecretKey,
        plain: String,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // the key of the alias, a new one when there is none or it can not be read
    private fun key(): SecretKey = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull() ?: newKey()

    private fun newKey(): SecretKey {
        runCatching { keyStore().deleteEntry(alias) }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
