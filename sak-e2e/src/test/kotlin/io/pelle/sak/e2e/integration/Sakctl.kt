package io.pelle.sak.e2e.integration

import java.io.File
import java.util.concurrent.TimeUnit

// the command line of sakctl: the binary of `cargo build` (SAK_E2E_SAKCTL points elsewhere),
// run with the token of the project; every line it prints goes to the output of the test
class Sakctl(
    private val token: String,
) {
    private val binary: File =
        File(System.getenv("SAK_E2E_SAKCTL") ?: System.getProperty("sak.e2e.sakctl") ?: "sakctl").also {
            check(it.canExecute()) { "no sakctl at $it, run `cargo build` in sak-cli/src-tauri" }
        }

    /** runs `sakctl <arguments>` and answers its exit code */
    fun run(vararg arguments: String): Int = output(*arguments).first

    /** runs `sakctl <arguments>` and answers its exit code and the lines it printed */
    fun output(vararg arguments: String): Pair<Int, List<String>> {
        println("> sakctl ${arguments.joinToString(" ")}")
        val process =
            ProcessBuilder(listOf(binary.path) + arguments)
                .redirectErrorStream(true)
                .apply {
                    environment()["HCLOUD_TOKEN"] = token
                    // the settings come as arguments, the variables of the developer stay out
                    environment().keys.removeIf { it.startsWith("SAK_") }
                }.start()
        val lines = mutableListOf<String>()
        process.inputStream.bufferedReader().forEachLine {
            println(it)
            lines += it
        }
        check(process.waitFor(60, TimeUnit.MINUTES)) { "sakctl ${arguments.first()} did not end" }
        return process.exitValue() to lines
    }
}
