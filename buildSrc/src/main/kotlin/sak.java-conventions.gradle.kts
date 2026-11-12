plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // a test JUnit can not run fails the run instead of being left out with a warning (TD-4): e.g. a
    // test of kotlin whose expression body returns a value (fun x() = runBlocking { a shouldBe b })
    // is no void method, which JUnit skips
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
}
