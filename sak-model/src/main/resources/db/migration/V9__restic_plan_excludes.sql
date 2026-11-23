-- what a run of a backup plan leaves out: the patterns of restic, one a line, with comments as the
-- user wrote them, and whether folders marked as caches (CACHEDIR.TAG) are left out. A plan made
-- before leaves out nothing, as before
ALTER TABLE restic_backup_plans ADD COLUMN excludes TEXT NOT NULL DEFAULT '';
ALTER TABLE restic_backup_plans ADD COLUMN exclude_caches BOOLEAN NOT NULL DEFAULT FALSE;
