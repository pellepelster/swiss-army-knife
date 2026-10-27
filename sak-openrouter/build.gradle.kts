// the client of openrouter, which reads the text in images with a vision model
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
