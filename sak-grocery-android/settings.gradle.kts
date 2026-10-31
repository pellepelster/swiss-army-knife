// sak-grocery-android, the android app of the grocery lists: a gradle build of its own, apart from
// the build of the backend, so the android gradle plugin does not tie its versions to it
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

rootProject.name = "sak-grocery-android"
