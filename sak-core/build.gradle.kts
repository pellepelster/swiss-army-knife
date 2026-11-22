plugins {
    id("sak.kotlin-conventions")
    `java-library`
    // the check of a catalog of texts, for the tests of every module
    `java-test-fixtures`
}

// what every part of the backend shares and that is not the api: the texts and their catalogs, health,
// the limits of requests, the mail sender, how an address is written to the logs. No Ktor
dependencies {
    implementation(libs.kotlinx.serialization.json)
    // reads the catalogs of the texts (Catalogs)
    implementation(libs.snakeyaml)
    implementation(libs.angus.mail)

    testImplementation(libs.greenmail.junit5)
    // greenmail alone asks for an old slf4j; this is the one the backend runs with
    testImplementation(libs.slf4j.api)
}
