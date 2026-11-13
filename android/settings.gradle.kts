// a gradle build of its own, apart from the backend, so the android gradle plugin does not tie its
// versions to it
pluginManagement {
    // sak.android.app, sak.android.library
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sak-android"

// apps.txt is the one list of the apps: mise.toml and sak-i18n read it too
val apps =
    file("apps.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

include(":api", ":core", ":testing")
apps.forEach { include(":$it") }
