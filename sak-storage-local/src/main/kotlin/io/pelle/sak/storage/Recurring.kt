package io.pelle.sak.storage

import java.time.Duration

// what runs a job in the background, now and again every period after it ended, e.g. the background
// jobs of the backend; a job that fails is its to handle, the next run follows anyway
fun interface Recurring {
    fun every(
        what: String,
        period: Duration,
        job: () -> Unit,
    )
}
