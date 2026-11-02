// the android apps of SAK: a gradle build of its own, apart from the build of the backend, so the
// android gradle plugin does not tie its versions to it. core is what every app shares (the
// deployment, the sign-in, the session, the api client, the update check, the theme), each app a
// module of its own on top of it
pluginManagement {
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

include(":api", ":core", ":testing", ":grocery", ":notes")
