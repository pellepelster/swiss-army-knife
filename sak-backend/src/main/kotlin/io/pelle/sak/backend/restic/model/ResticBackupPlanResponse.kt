package io.pelle.sak.backend.restic.model

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
)
