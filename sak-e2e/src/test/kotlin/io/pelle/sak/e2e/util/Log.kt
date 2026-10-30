package io.pelle.sak.e2e.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * one line of what a test does or waits for, with the time, at once on the output of the run; the
 * integration tests take many minutes. `of` is the deployment the step is about, if any
 */
fun step(
    message: String,
    of: Deployment? = null,
) {
    val prefix = of?.let { "[${it.name}] " } ?: ""
    println("${LocalTime.now().format(TIME)} $prefix$message")
    System.out.flush()
}
