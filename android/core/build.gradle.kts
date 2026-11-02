// what every android app of SAK shares: the deployment, the sign-in by link or password, the session
// kept encrypted, the base of the api client, the update check, the theme and the texts of no app
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.pelle.sak.core"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // what the apps build on: compose, the client of the api and the coroutines come with core
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    api(libs.compose.ui)
    api(libs.activity.compose)
    api(libs.lifecycle.viewmodel.compose)
    api(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    api(platform(libs.ktor.bom))
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
