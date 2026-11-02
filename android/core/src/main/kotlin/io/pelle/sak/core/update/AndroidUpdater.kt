package io.pelle.sak.core.update

import io.pelle.sak.core.R
import io.pelle.sak.core.uiText
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

// the releases of sak on github: the tag v1.4.2 has the apk sak-<app>-1.4.2.apk of every app and its checksum
const val RELEASES_URL = "https://github.com/pellepelster/swiss-army-knife/releases/download"

// the apk of a release of sak from github, checked against the checksum of the release, handed to
// the installer of android. Android installs it only when the key of the release signed it, as
// it signed this app; it asks the user, from Android 12 on not once this app installed itself
class AndroidUpdater(
    private val context: Context,
    engine: HttpClientEngine,
    // the name of the app in the name of its apk, e.g. grocery for sak-grocery-1.4.2.apk
    private val app: String,
) : Updater {
    private val client =
        HttpClient(engine) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
            }
        }

    override suspend fun install(version: String) {
        val apk = File(context.cacheDir, "sak-$app-update.apk")
        try {
            download(version, apk)
            withContext(Dispatchers.IO) { commit(apk) }
        } finally {
            apk.delete()
        }
    }

    private suspend fun download(
        version: String,
        apk: File,
    ) {
        val name = "sak-$app-$version.apk"
        val sums = client.get("$RELEASES_URL/v$version/$name.sha256")
        if (!sums.status.isSuccess()) throw IOException("the checksum of $name answered ${sums.status}")
        val expected = sums.bodyAsText().trim().substringBefore(' ').lowercase()
        client
            .prepareGet("$RELEASES_URL/v$version/$name") { timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS } }
            .execute { response ->
                if (!response.status.isSuccess()) throw IOException("$name answered ${response.status}")
                withContext(Dispatchers.IO) { apk.outputStream().use { response.bodyAsChannel().copyTo(it) } }
            }
        val actual = withContext(Dispatchers.IO) { sha256(apk) }
        if (actual != expected) throw UpdateException(uiText(R.string.error_update_damaged, version))
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // the installer fills in the status, so the intent is mutable
            val status =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            session.commit(status.intentSender)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 60_000L
        const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
        const val BUFFER_SIZE = 64 * 1024
    }
}

// the confirmation of an update the installer of android asks the user for: android shows it only
// from an app that is seen (the start of an activity from the background is blocked), so it waits
// for the next activity of the app that is seen when there is none (AN-12)
object PendingInstall {
    private var confirm: Intent? = null
    private var shown: Activity? = null

    @Synchronized
    fun ask(intent: Intent) {
        val activity = shown
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            confirm = intent
        }
    }

    // an activity of the app is seen: the confirmation that waits shows
    @Synchronized
    fun seen(activity: Activity) {
        shown = activity
        confirm?.let {
            confirm = null
            activity.startActivity(it)
        }
    }

    @Synchronized
    fun unseen() {
        shown = null
    }
}

// what the installer of android answers: the confirmation it asks the user for is shown, a failure
// is a short message; on success the app ends and starts as the new version
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                confirm?.let { PendingInstall.ask(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            // the user declined, nothing to say
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val text = message?.let { context.getString(R.string.update_not_installed_because, it) } ?: context.getString(R.string.update_not_installed)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
