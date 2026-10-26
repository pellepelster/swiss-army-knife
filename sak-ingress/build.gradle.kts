plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
