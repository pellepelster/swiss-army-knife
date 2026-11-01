package io.pelle.sak.i18n

import java.nio.file.Path
import kotlin.system.exitProcess

// generate: writes the files of every platform from i18n/texts.yml; check: names the ones that are
// not up to date. Runs in the root of the repository (mise run i18n:generate)
fun main(args: Array<String>) {
    val root = Path.of(args.getOrElse(1) { "." }).toAbsolutePath().normalize()
    try {
        when (args.firstOrNull()) {
            "generate" -> Generator.generate(root).forEach { println("written $it") }
            "check" -> {
                val outdated = Generator.outdated(root)
                if (outdated.isNotEmpty()) {
                    System.err.println("not up to date with $SOURCE, run mise run i18n:generate:")
                    outdated.forEach { System.err.println("  $it") }
                    exitProcess(1)
                }
            }
            else -> {
                System.err.println("usage: sak-i18n generate|check [root of the repository]")
                exitProcess(2)
            }
        }
    } catch (e: TextsException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}
