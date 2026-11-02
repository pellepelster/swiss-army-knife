// the bodies of the api of sak-backend, generated at build time from the openapi document at the root
// of the repository (openapi.json, mise run api:generate writes it from the routes of sak-backend):
// kotlinx.serialization data classes, no android, never edited or copied by hand
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

// the schemas the apps read and send; the others (e.g. the pages, which a generic PageResponse of
// core reads) are not generated
val models =
    listOf(
        "InfoResponse",
        "LoginRequest",
        "ExchangeRequest",
        "PasswordLoginRequest",
        "ExchangeResponse",
        "WhoAmIResponse",
        "PaginationResponse",
        "FileResponse",
        "ApiErrorMessage",
        "ApiErrorResponse",
        "GroceryListResponse",
        "GroceryListRequest",
        "GroceryItemResponse",
        "GroceryNewItemRequest",
        "GroceryItemRequest",
        "GroceryPhotoRequest",
        "GroceryCategoryResponse",
        "GroceryCategoryRequest",
        "GroceryCategoryOrderRequest",
        "GroceryItemCategoryRequest",
        "LabelResponse",
        "PhotoResponse",
        "RecordingResponse",
        "NoteResponse",
        "NoteRequest",
        "DiscoverTextResponse",
    )

val spec = rootProject.layout.projectDirectory.file("../openapi.json")
val generated = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(spec.asFile.path)
    outputDir.set(generated.get().asFile.path)
    modelPackage.set("io.pelle.sak.api.model")
    globalProperties.set(
        mapOf(
            "models" to models.joinToString(","),
            "modelDocs" to "false",
            "modelTests" to "false",
        ),
    )
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "sourceFolder" to "src/main/kotlin",
            "enumPropertyNaming" to "UPPERCASE",
        ),
    )
    // the document has no formats: an integer is an Int, an amount a Double, a time a String
    typeMappings.set(mapOf("number" to "kotlin.Double", "integer" to "kotlin.Int"))
    cleanupOutput.set(true)
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir(generated.map { it.dir("src/main/kotlin") })
    }
}

// the spec is a path to the plugin; its content is the input, so a new openapi.json generates anew
tasks.named("openApiGenerate") {
    inputs.file(spec).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generated)
}

tasks.named("compileKotlin") { dependsOn(tasks.named("openApiGenerate")) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
