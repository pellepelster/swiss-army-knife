plugins {
    id("sak.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":sak-config"))
    implementation(project(":sak-api"))
    implementation(project(":sak-model"))
    implementation(project(":sak-s3"))
    implementation(project(":sak-storage"))
    implementation(project(":sak-ingress"))
    implementation(project(":sak-auth"))
    implementation(project(":sak-logs"))
    implementation(project(":sak-openrouter"))
    implementation(project(":sak-dns"))
    runtimeOnly(project(":sak-frontend"))
    implementation(libs.caffeine)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.routing.openapi)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.logback.classic)
    testImplementation(testFixtures(project(":sak-api")))
    testImplementation(testFixtures(project(":sak-model")))

    testImplementation(libs.greenmail.junit5)

    testImplementation(platform(libs.awssdk.bom))
    testImplementation(libs.awssdk.s3)
    testImplementation(libs.awssdk.apache.client)
    testImplementation(libs.kotlinx.serialization.json)
}

application {
    mainClass = "io.pelle.sak.backend.SakBackendKt"
    // measured under load on 2026-11-02: about 100 MB of live heap at the peak. The serial gc fits one household
    // and gives the heap back at a full gc; the client compiler only, its code is half as large; an
    // OutOfMemoryError ends the backend, systemd starts it again
    applicationDefaultJvmArgs =
        listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseSerialGC",
            "-XX:+UseCompactObjectHeaders",
            "-Xmx192m",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:TieredStopAtLevel=1",
            "-XX:MaxDirectMemorySize=64m",
            "-XX:+ExitOnOutOfMemoryError",
        )
}

// the docker image is built from the installDist output
tasks.distTar {
    enabled = false
}

tasks.distZip {
    enabled = false
}

// SakBackendTest validates the config template of the backend role. RecipeIngredientsTest asks
// openrouter (a few cents each run) only when selected by name:
// ./gradlew :sak-backend:test --tests '*RecipeIngredientsTest*' --rerun
tasks.test {
    inputs.file(rootProject.file("ansible/roles/backend/templates/backend-config.yaml.j2")).withPathSensitivity(PathSensitivity.RELATIVE)
    passSecrets(mapOf("OPENROUTER_API_KEY" to "openrouter/pelle/api-key"), selectedTest = "RecipeIngredientsTest")
}

// runs with the image the role deploys; needs docker, so not part of check (the console's e2eTest depends on it)
val vectorTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the unit tests of the vector configuration in docker"
    val files = rootProject.file("ansible/roles/backend/files")
    val image =
        rootProject
            .file("ansible/roles/backend/defaults/main.yml")
            .readLines()
            .single { it.startsWith("backend_vector_image:") }
            .substringAfter(":")
            .trim()
    inputs.dir(files)
    commandLine("docker", "run", "--rm", "--volume", "$files:/etc/vector:ro", image, "test", "/etc/vector/vector.yaml", "/etc/vector/vector-tests.yaml")
}
