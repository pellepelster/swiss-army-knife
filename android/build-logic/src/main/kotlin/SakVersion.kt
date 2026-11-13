import org.gradle.api.GradleException

// the version of a build, from VERSION: latest, or x.y.z for a release. The versionCode counts up
// with every release, so android takes it as an update: 1.4.2 is 1_004_002, latest is 1. A minor or
// patch of 1000 or more would collide with the next major or minor, so it fails the build
data class SakVersion(
    val name: String,
    val code: Int,
) {
    val latest get() = name == LATEST

    companion object {
        const val LATEST = "latest"
        private val RELEASE = Regex("""(\d{1,4})\.(\d{1,3})\.(\d{1,3})""")

        fun of(version: String?): SakVersion {
            val name = version?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: LATEST
            if (name == LATEST) return SakVersion(name, 1)
            val (major, minor, patch) =
                RELEASE.matchEntire(name)?.destructured?.toList()?.map { it.toInt() }
                    ?: throw GradleException("VERSION $version is neither $LATEST nor a release x.y.z (minor and patch below 1000)")
            if (major > 2_000) throw GradleException("VERSION $version: the major $major is too large for a versionCode")
            return SakVersion(name, major * 1_000_000 + minor * 1_000 + patch)
        }
    }
}
