plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    // the objects of the buckets are blobs of the binary storage (MODEL-7)
    api(project(":sak-storage"))

    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.dataformat.xml)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.ktor.server.netty)
    testImplementation(platform(libs.awssdk.bom))
    testImplementation(libs.awssdk.s3)
}
