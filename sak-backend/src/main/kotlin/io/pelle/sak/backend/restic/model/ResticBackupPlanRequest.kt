package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// a backup plan to create, or the values that replace those of one (BACKUP-5)
@Serializable
data class ResticBackupPlanRequest(
    val name: String,
    // a repository of the user
    val repositoryId: Int,
    // the host name of the computer the paths are on
    val host: String,
    // absolute paths of files or folders, in their order (BACKUP-6)
    val paths: List<String>,
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
