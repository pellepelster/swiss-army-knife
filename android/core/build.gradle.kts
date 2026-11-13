// what every android app of SAK shares
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("sak.android.library")
}

android {
    namespace = "io.pelle.sak.core"

    // sak.android.app adds the same icons to each app
    sourceSets.getByName("main").res.srcDir(rootProject.file("../sak-theme/images/android/icons"))

    buildFeatures {
        compose = true
    }
}

dependencies {
    // compose, ktor and the api come with core
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    api(libs.compose.ui)
    api(libs.activity.compose)
    api(libs.lifecycle.viewmodel.compose)
    api(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    // the picker of the icon of a list (EmojiSheet)
    implementation(libs.emoji2.emojipicker)
    // the scan of the QR code that connects the app: the camera, decoded on the phone (PRIVACY-7)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    api(platform(libs.ktor.bom))
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.kotlinx.serialization.json)
    api(project(":api"))

    testImplementation(libs.junit)
}
