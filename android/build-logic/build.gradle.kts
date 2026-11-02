// the convention plugins: what every app and every library of android/ shares, so a module keeps
// only its id, its namespace and its own dependencies. The android gradle plugin comes from the
// plugins block of each module (compileOnly here, so its version is the one of the catalog)
plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("sakAndroidApp") {
            id = "sak.android.app"
            implementationClass = "SakAndroidAppPlugin"
        }
        register("sakAndroidLibrary") {
            id = "sak.android.library"
            implementationClass = "SakAndroidLibraryPlugin"
        }
    }
}
