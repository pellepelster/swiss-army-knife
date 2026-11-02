// the android apps of SAK: a gradle build of its own, apart from the build of the backend, so the
// android gradle plugin does not tie its versions to it. core is what every app shares (the
// deployment, the sign-in, the session, the api client, the update check, the theme), each app a
// module of its own on top of it
pluginManagement {
    // the conventions of the apps and the libraries (sak.android.app, sak.android.library)
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

// the apps are the lines of apps.txt, the one list of them: mise.toml (the release, the run and the
// install of an app) and sak-i18n (the texts of each app) read it too
val apps =
    file("apps.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

include(":api", ":core", ":testing")
apps.forEach { include(":$it") }
