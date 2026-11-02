package io.pelle.sak.core.update

import io.pelle.sak.core.UiText

// the version of the app the deployment runs, newer than the app; busy while it is downloaded and
// handed to the installer of android
data class AppUpdate(
    val version: String,
    val busy: Boolean = false,
    val error: UiText? = null,
)

// the update of the app to a version of sak: the apk of its release downloaded, checked and handed
// to the installer of android, which asks the user; a failure is an exception, an UpdateException
// with the reason the user reads
fun interface Updater {
    suspend fun install(version: String)
}

class UpdateException(
    val text: UiText,
) : Exception(text.toString())

// the version of the deployment when it is newer than the one of the app, both released versions
// (1.4.2); a build of no release (latest) is never updated, nor is a deployment without a version
fun newer(
    app: String,
    deployment: String?,
): String? {
    fun parts(version: String?) = version?.let { RELEASE.matchEntire(it) }?.groupValues?.drop(1)?.map { it.toInt() }
    val current = parts(app) ?: return null
    val offered = parts(deployment) ?: return null
    val compared = current.zip(offered).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
    return deployment.takeIf { compared < 0 }
}

// a released version, 1.4.2, not latest
fun isRelease(version: String?) = version != null && RELEASE.matches(version)

private val RELEASE = Regex("""(\d+)\.(\d+)\.(\d+)""")
