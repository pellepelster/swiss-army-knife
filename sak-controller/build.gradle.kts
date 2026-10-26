plugins {
    id("sak.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":sak-config"))
    implementation(project(":sak-s3"))
    implementation(project(":sak-caddy"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.awssdk.bom))
    testImplementation(libs.awssdk.s3)
    testImplementation(libs.awssdk.apache.client)
    testImplementation(libs.kotlinx.serialization.json)
}

application {
    mainClass = "io.pelle.sak.controller.SakControllerKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// the controller is shipped as docker image, built from the installDist output
tasks.distTar {
    enabled = false
}

tasks.distZip {
    enabled = false
}

// SakControllerTest validates the deployed configuration
tasks.test {
    inputs.file(rootProject.file("config/controller-config.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
