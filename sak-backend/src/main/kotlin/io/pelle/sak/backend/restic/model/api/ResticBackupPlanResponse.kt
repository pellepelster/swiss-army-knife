package io.pelle.sak.backend.restic.model.api

import kotlinx.serialization.Serializable

// a backup plan of restic (BACKUP-5)
@Serializable
data class ResticBackupPlanResponse(
    val id: Int,
    val name: String,
    val repositoryId: Int,
    val host: String,
    val paths: List<String>,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
    // how many of the last, daily, weekly, monthly and yearly snapshots a run keeps, each from 1 to
    // 1000; none keeps them all (BACKUP-8)
    val keepLast: Int? = null,
    val keepDaily: Int? = null,
    val keepWeekly: Int? = null,
    val keepMonthly: Int? = null,
    val keepYearly: Int? = null,
    // what a run leaves out: the patterns of restic, one a line, with comments (#) and blank lines;
    // none when empty
    val excludes: String = "",
    // whether a run leaves out the folders marked as caches (CACHEDIR.TAG)
    val excludeCaches: Boolean = false,
)
