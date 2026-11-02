// what the tests of every app share, without android: a fake backend of one deployment for the client
// of the api, and the english texts of core and the app
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.pelle.sak.testing"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    api(platform(libs.ktor.bom))
    api(libs.ktor.client.mock)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
}
