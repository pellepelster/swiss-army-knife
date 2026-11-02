// what the tests of every app share, without android: a fake backend of one deployment for the client
// of the api, and the english texts of core and the app
// (sdks and java 17 from sak.android.library, build-logic)
plugins {
    alias(libs.plugins.android.library)
    id("sak.android.library")
}

android {
    namespace = "io.pelle.sak.testing"
}

dependencies {
    api(project(":core"))
    api(platform(libs.ktor.bom))
    api(libs.ktor.client.mock)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
}
