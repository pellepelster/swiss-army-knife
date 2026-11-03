import org.gradle.api.GradleException
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// the secrets of the tests, from pass, read once a build and only when a test task that needs them
// runs, so the tests get them from gradle as well as from IntelliJ. A variable already set in the
// environment wins over pass (the github workflow, another key for a run). pass takes its store
// from PASSWORD_STORE_DIR, ~/.password-store unless set, and the first line of an entry
abstract class PassSecrets : BuildService<BuildServiceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    private val logger = Logging.getLogger(PassSecrets::class.java)

    private val secrets = ConcurrentHashMap<String, Result<String>>()

    // the value of the variable, from pass when it is not set; null when neither has it
    fun secret(variable: String, path: String): String? =
        System.getenv(variable)?.takeIf { it.isNotBlank() }
            ?: secrets.computeIfAbsent(path) { runCatching { read(it) } }.getOrNull()

    private fun read(path: String): String {
        logger.lifecycle("reading the pass secret '$path'")
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("pass", "show", path)
            standardOutput = output
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        val value = output.toString(Charsets.UTF_8).lineSequence().firstOrNull()?.trim().orEmpty()
        if (result.exitValue != 0 || value.isEmpty()) {
            throw GradleException("no pass secret '$path'")
        }
        return value
    }
}

// the variables of the secrets in the environment of the test, each from its pass entry unless it is
// set; with required a missing one fails the task, otherwise the variable stays unset (the tests
// that need it are skipped). With selectedTest the secrets are read only when the test is selected
// by name (--tests, what IntelliJ passes for a test it runs), so a test that costs money does not
// run with each build
fun Test.passSecrets(
    secrets: Map<String, String>,
    required: Boolean = false,
    selectedTest: String? = null,
) {
    val service = project.gradle.sharedServices.registerIfAbsent("passSecrets", PassSecrets::class) {}
    usesService(service)
    doFirst {
        val test = this as Test
        if (selectedTest != null && selected(test).none { selectedTest in it }) return@doFirst
        val missing = mutableListOf<String>()
        secrets.forEach { (variable, path) ->
            val value = service.get().secret(variable, path)
            if (value == null) missing += "$variable (pass $path)" else test.environment(variable, value)
        }
        if (missing.isNotEmpty()) {
            val message = "set ${missing.joinToString(", ")}"
            if (required) throw GradleException(message) else logger.lifecycle("$message, the tests that need it are skipped")
        }
    }
}

// the patterns of --tests, which only the filter of gradle itself keeps apart from includeTestsMatching
private fun selected(test: Test): Set<String> =
    test.filter.includePatterns + ((test.filter as? DefaultTestFilter)?.commandLineIncludePatterns ?: emptySet())
