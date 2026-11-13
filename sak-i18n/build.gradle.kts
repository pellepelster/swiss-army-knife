plugins {
    id("sak.kotlin-conventions")
    application
}

// the texts of every app in i18n/texts.yml, written into the files the backend, the web apps, the
// android app and sakctl read (mise run i18n:generate); its test fails when they are not up to date
dependencies {
    implementation(libs.snakeyaml)
}

application {
    mainClass = "io.pelle.sak.i18n.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.test {
    systemProperty("sak.root", rootDir.absolutePath)
    // the texts and the generated files are outside of the module
    inputs.file(rootDir.resolve("i18n/texts.yml"))
    inputs.file(rootDir.resolve("android/apps.txt"))
    // the texts of core and of every app of android/apps.txt
    val androidModules =
        listOf("core") +
            rootDir.resolve("android/apps.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
    inputs.files(
        rootDir.resolve("sak-api/src/main/resources/i18n"),
        rootDir.resolve("sak-frontend/src/app/i18n/catalog.en.ts"),
        rootDir.resolve("sak-frontend/src/app/i18n/catalog.de.ts"),
        rootDir.resolve("sak-cli/src-tauri/locales"),
    )
    androidModules.forEach { module ->
        inputs.files(rootDir.resolve("android/$module/src/main/res/values"), rootDir.resolve("android/$module/src/main/res/values-de"))
    }
}
