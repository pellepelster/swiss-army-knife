package io.pelle.sak.e2e.util

import java.io.File
import java.util.concurrent.TimeUnit

// the command line of sakctl: the binary of `cargo build` (SAK_E2E_SAKCTL points elsewhere),
// run with the token of the project and the password of a deployment in SAK_PASSWORD, and with
// `environment`, e.g. the user and the password at the mail server (SAK_SMTP_USERNAME,
// SAK_SMTP_PASSWORD), never arguments; every line it prints goes to the output of the test
class Sakctl(
    private val token: String,
    private val password: String,
    private val environment: Map<String, String> = emptyMap(),
    // before each line it prints, so two runs at once can be told apart
    private val prefix: String = "",
) {
    private val binary: File =
        File(System.getenv("SAK_E2E_SAKCTL") ?: System.getProperty("sak.e2e.sakctl") ?: "sakctl").also {
            check(it.canExecute()) { "no sakctl at $it, run `cargo build` in sak-cli/src-tauri" }
        }

    /** runs `sakctl <arguments>` and answers its exit code */
    fun run(vararg arguments: String): Int = output(*arguments).first

    /**
     * runs `sakctl <arguments>` and answers its exit code and the lines it printed; `input` is its
     * standard input, e.g. what a command of `sakctl ssh` reads, never an argument
     */
    fun output(
        vararg arguments: String,
        input: String = "",
    ): Pair<Int, List<String>> {
        println("$prefix> sakctl ${arguments.joinToString(" ")}")
        val process =
            ProcessBuilder(listOf(binary.path) + arguments)
                .redirectErrorStream(true)
                .apply {
                    environment()["HCLOUD_TOKEN"] = token
                    // the settings come as arguments, the variables of the developer stay out
                    environment().keys.removeIf { it.startsWith("SAK_") }
                    environment()["SAK_PASSWORD"] = password
                    environment().putAll(this@Sakctl.environment)
                }.start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val lines = mutableListOf<String>()
        process.inputStream.bufferedReader().forEachLine {
            println("$prefix$it")
            System.out.flush()
            lines += it
        }
        check(process.waitFor(60, TimeUnit.MINUTES)) { "sakctl ${arguments.first()} did not end" }
        return process.exitValue() to lines
    }
}
