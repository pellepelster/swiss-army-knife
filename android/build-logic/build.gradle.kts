// the convention plugins of the android modules. The android gradle plugin is compileOnly here: the
// plugins block of each module brings it, in the version of the catalog
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
