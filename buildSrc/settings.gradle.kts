// the kotlin-dsl plugin and what it needs, e.g. kotlin-stdlib, from maven central too, where the
// plugin portal does not have them
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
