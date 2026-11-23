-- the position of a file of a note and of a path of a backup plan is unique among the live rows of
-- its note or plan, as the position of a domain of a bucket is (DM-5). The live rows are numbered
-- 0, 1, 2… first, by their position and then their id, so rows of an older bug can not stop the
-- index. The numbers are computed before they are written: an update that reads the table it writes
-- would see some of its own changes
CREATE TEMP TABLE notes_files_positions AS
SELECT id, ROW_NUMBER() OVER (PARTITION BY note_id ORDER BY position, id) - 1 AS position
FROM notes_files WHERE deleted_at IS NULL;
UPDATE notes_files SET position =
(SELECT numbered.position FROM notes_files_positions AS numbered WHERE numbered.id = notes_files.id)
WHERE id IN (SELECT id FROM notes_files_positions AS numbered WHERE numbered.position != notes_files.position);
DROP TABLE notes_files_positions;
CREATE UNIQUE INDEX notes_files_position_key ON notes_files (note_id, position) WHERE deleted_at IS NULL;

CREATE TEMP TABLE restic_backup_plan_paths_positions AS
SELECT id, ROW_NUMBER() OVER (PARTITION BY plan_id ORDER BY position, id) - 1 AS position
FROM restic_backup_plan_paths WHERE deleted_at IS NULL;
UPDATE restic_backup_plan_paths SET position =
(SELECT numbered.position FROM restic_backup_plan_paths_positions AS numbered WHERE numbered.id = restic_backup_plan_paths.id)
WHERE id IN (SELECT id FROM restic_backup_plan_paths_positions AS numbered WHERE numbered.position != restic_backup_plan_paths.position);
DROP TABLE restic_backup_plan_paths_positions;
CREATE UNIQUE INDEX restic_backup_plan_paths_position_key ON restic_backup_plan_paths (plan_id, position) WHERE deleted_at IS NULL;
