// the conventions of the android modules (sak.android.app, sak.android.library), an included build
// of android/ (settings.gradle.kts); it reads the versions of android/gradle/libs.versions.toml
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
