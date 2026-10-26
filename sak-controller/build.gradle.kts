plugins {
    id("sak.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":sak-config"))
    implementation(project(":sak-s3"))
    implementation(project(":sak-ingress"))
    implementation(project(":sak-auth"))
    implementation(project(":sak-logs"))
    runtimeOnly(project(":sak-console"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.routing.openapi)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.greenmail.junit5)

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

// the unit tests of the vector configuration the controller role deploys, run with the image
// the role deploys (needs docker, so not part of check; the console's e2eTest depends on it)
val vectorTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the unit tests of the vector configuration in docker"
    val files = rootProject.file("ansible/roles/controller/files")
    val image =
        rootProject
            .file("ansible/roles/controller/defaults/main.yml")
            .readLines()
            .single { it.startsWith("controller_vector_image:") }
            .substringAfter(":")
            .trim()
    inputs.dir(files)
    commandLine("docker", "run", "--rm", "--volume", "$files:/etc/vector:ro", image, "test", "/etc/vector/vector.yaml", "/etc/vector/vector-tests.yaml")
}
