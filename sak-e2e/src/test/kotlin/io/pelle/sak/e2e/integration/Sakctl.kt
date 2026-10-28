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
    fun run(vararg arguments: String): Int {
        println("> sakctl ${arguments.joinToString(" ")}")
        val process =
            ProcessBuilder(listOf(binary.path) + arguments)
                .redirectErrorStream(true)
                .apply {
                    environment()["HCLOUD_TOKEN"] = token
                    // the password comes as an argument, the variables of the developer stay out
                    environment().remove("SAK_NAME")
                    environment().remove("SAK_PASSWORD")
                    environment().remove("SAK_LOCATION")
                }.start()
        process.inputStream.bufferedReader().forEachLine { println(it) }
        check(process.waitFor(60, TimeUnit.MINUTES)) { "sakctl ${arguments.first()} did not end" }
        return process.exitValue()
    }
}
