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
        "GrocerySuggestionResponse",
        "GrocerySettingsResponse",
        "GroceryDiscoveredResponse",
        "GroceryDiscoveredItem",
        "LabelResponse",
        "PhotoResponse",
        "RecordingResponse",
        "NoteResponse",
        "NoteRequest",
        "DiscoverTextResponse",
        "NotesSettingsResponse",
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
    // the document has no formats: an integer is an Int, an amount a Double, a time a String. Int is
    // enough also for the size of a file (FileResponse.size): the backend takes files of at most
    // 64 MB (FileService.DEFAULT_MAX_SIZE)
    typeMappings.set(mapOf("number" to "kotlin.Double", "integer" to "kotlin.Int"))
    cleanupOutput.set(true)
}

// the spec is a path to the plugin; its content is the input, so a new openapi.json generates anew
val generate =
    tasks.named("openApiGenerate") {
        inputs.file(spec).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(generated)
    }

// java 17 like the android modules, with the jdk gradle runs on (no toolchain to install); the
// generated sources come from the task, so an import of the IDE generates them too
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.main {
        kotlin.srcDir(generate.map { generated.get().dir("src/main/kotlin") })
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
