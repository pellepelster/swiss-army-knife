plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)

    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.dataformat.xml)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.ktor.server.netty)
    testImplementation(platform(libs.awssdk.bom))
    testImplementation(libs.awssdk.s3)
}
