// the convention plugins of the android modules. The android gradle plugin is compileOnly here: the
// root build brings it and the compose plugin (apply false), in the versions of the catalog; the
// convention plugins apply both by their ids
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
