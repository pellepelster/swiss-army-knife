package io.pelle.sak.backend.restic

import io.pelle.sak.core.Catalog

// the texts of the repositories of restic (backend.restic in i18n/texts.yml)
object ResticTexts : Catalog("restic") {
    val INVALID_NAME = text("invalid_name")
    val REPOSITORY_EXISTS = text("repository_exists")
    val INVALID_HOST = text("invalid_host")
    val NO_PATHS = text("no_paths")
    val TOO_MANY_PATHS = text("too_many_paths")
    val DOUBLE_PATH = text("double_path")
    val INVALID_PATH = text("invalid_path")
    val UNKNOWN_REPOSITORY = text("unknown_repository")
    val PLAN_EXISTS = text("plan_exists")
    val INVALID_KEEP = text("invalid_keep")
}
