// the app: kotlin (built into the android gradle plugin), jetpack compose, ktor with okhttp
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// the version of SAK (mise.toml takes the v off): 1.4.2 is the versionCode 10402, so every release
// counts up and android takes it as an update; latest, every build but a release, is 1
val sakVersion = System.getenv("VERSION")?.removePrefix("v") ?: "latest"
val sakVersionCode =
    Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(sakVersion)?.destructured?.let { (major, minor, patch) ->
        major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    } ?: 1

android {
    namespace = "io.pelle.sak.grocery"
    // what the libraries of compose need to compile against; the app runs from minSdk on
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // also the scheme of the signin link (sak-frontend/src/app/signin/signin.ts)
        applicationId = "io.pelle.sak.grocery"
        minSdk = 26
        targetSdk = 36
        versionCode = sakVersionCode
        versionName = sakVersion
    }

    // the key of the release, from the keystore mise run grocery:android:release:build decodes out of
    // the secrets (github:secrets:update); without it the release apk stays unsigned
    signingConfigs {
        System.getenv("ANDROID_KEYSTORE_FILE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storeType = "PKCS12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    // the picker of the icon of a list, the emoji of the system (androidx)
    implementation(libs.emoji2.emojipicker)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.client.mock)
}
