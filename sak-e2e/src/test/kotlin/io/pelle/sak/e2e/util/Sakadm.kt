package io.pelle.sak.e2e.util

import java.io.File
import java.util.concurrent.TimeUnit

// the command line of sakadm: the binary of `cargo build` (SAK_E2E_SAKADM points elsewhere),
// run with the token of the project and the password of a deployment in SAK_PASSWORD, and with
// `environment`, e.g. the user and the password at the mail server (SAK_SMTP_USERNAME,
// SAK_SMTP_PASSWORD), never arguments; every line it prints goes to the output of the test
class Sakadm(
    private val token: String,
    private val password: String,
    private val environment: Map<String, String> = emptyMap(),
    // before each line it prints, so two runs at once can be told apart
    private val prefix: String = "",
) {
    private val binary: File =
        File(System.getenv("SAK_E2E_SAKADM") ?: System.getProperty("sak.e2e.sakadm") ?: "sakadm").also {
            check(it.canExecute()) { "no sakadm at $it, run `cargo build` in sak-admin-cli/src-tauri" }
        }

    /** runs `sakadm <arguments>` and answers its exit code */
    fun run(vararg arguments: String): Int = output(*arguments).first

    /**
     * runs `sakadm <arguments>` and answers its exit code and the lines it printed; `input` is its
     * standard input, e.g. what a command of `sakadm ssh` reads, never an argument
     */
    fun output(
        vararg arguments: String,
        input: String = "",
    ): Pair<Int, List<String>> {
        println("$prefix> sakadm ${arguments.joinToString(" ")}")
        val process =
            ProcessBuilder(listOf(binary.path) + arguments)
                .redirectErrorStream(true)
                .apply {
                    environment()["HCLOUD_TOKEN"] = token
                    // the settings come as arguments, the variables of the developer stay out
                    environment().keys.removeIf { it.startsWith("SAK_") }
                    environment()["SAK_PASSWORD"] = password
                    environment().putAll(this@Sakadm.environment)
                }.start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val lines = mutableListOf<String>()
        process.inputStream.bufferedReader().forEachLine {
            println("$prefix$it")
            System.out.flush()
            lines += it
        }
        check(process.waitFor(60, TimeUnit.MINUTES)) { "sakadm ${arguments.first()} did not end" }
        return process.exitValue() to lines
    }
}
