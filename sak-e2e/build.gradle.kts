import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Duration

// end to end tests: the local tests, the apps in a browser against the local environment
// (e2eLocal, `mise run e2e:test:local`), and the integration tests, the command line of sakctl
// against a real Hetzner Cloud project (e2eIntegration, `mise run e2e:test:integration`). Neither
// runs with `gradle test` or `gradle build`, both need more than the build has
plugins {
    id("sak.kotlin-conventions")
}

dependencies {
    testImplementation(libs.playwright)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    enabled = false
}

val e2eSources = sourceSets.test.get()

fun e2e(
    name: String,
    tag: String,
    text: String,
    configure: Test.() -> Unit = {},
) = tasks.register<Test>(name) {
    description = text
    group = "verification"
    testClassesDirs = e2eSources.output.classesDirs
    classpath = e2eSources.runtimeClasspath
    useJUnitPlatform { includeTags(tag) }
    // what runs is outside the build, a run is never up to date
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
    configure()
}

e2e("e2eLocal", "local", "Runs the local tests: the apps against the environment of mise run local:run")

// the window of sakctl from its files and the fixtures of `cargo test window_fixtures -- --ignored`
fun Test.window() {
    systemProperty("sak.e2e.window.ui", rootDir.resolve("sak-cli/ui").path)
    systemProperty("sak.e2e.window.fixtures", rootDir.resolve("build/ui-fixtures/window.json").path)
}

e2e("e2eScreenshots", "screenshots", "Takes the screenshots of docs/screenshots/: the apps against the environment of mise run local:run, the window of sakctl") {
    systemProperty("sak.e2e.screenshots", rootDir.resolve("docs/screenshots").path)
    window()
}

e2e("e2eVideos", "video", "Records the videos of build/videos/: the apps against the environment of mise run local:run, the window of sakctl") {
    systemProperty("sak.e2e.videos", rootDir.resolve("build/videos").path)
    window()
}

e2e("e2eIntegration", "integration", "Runs the integration tests: the command line of sakctl against the Hetzner Cloud project of HCLOUD_TOKEN") {
    // the binary of `cargo build` in sak-cli/src-tauri, SAK_E2E_SAKCTL points elsewhere
    systemProperty("sak.e2e.sakctl", rootDir.resolve("sak-cli/src-tauri/target/debug/sakctl").path)
    // four servers created and provisioned one after the other
    timeout = Duration.ofMinutes(180)
}
