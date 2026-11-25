package io.pelle.sak.backend.restic

import io.pelle.sak.core.Catalog

// the texts of the repositories of restic (backend.restic in i18n/texts.yml)
object ResticTexts : Catalog("restic") {
    val INVALID_NAME = text("invalid_name")
    val INVALID_DESCRIPTION = text("invalid_description")
    val NO_NAME = text("no_name")
    val REPOSITORY_EXISTS = text("repository_exists")
    val FIRST_REPOSITORY = text("first_repository")
    val INVALID_HOST = text("invalid_host")
    val NO_PATHS = text("no_paths")
    val TOO_MANY_PATHS = text("too_many_paths")
    val DOUBLE_PATH = text("double_path")
    val INVALID_PATH = text("invalid_path")
    val UNKNOWN_REPOSITORY = text("unknown_repository")
    val PLAN_EXISTS = text("plan_exists")
    val INVALID_KEEP = text("invalid_keep")
    val TOO_MANY_EXCLUDES = text("too_many_excludes")
    val INVALID_EXCLUDE = text("invalid_exclude")
    val S3_LABEL = text("settings.s3.label")
    val S3_DESCRIPTION = text("settings.s3.description")
    val SCOPE_LABEL = text("settings.label")
    val SCOPE_DESCRIPTION = text("settings.description")
}
