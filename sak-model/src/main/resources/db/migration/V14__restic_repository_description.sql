-- a repository has a description the user reads, its name for the command line is derived from it
-- when it is created (BACKUP-1); neither changes afterwards. The repositories made before keep their
-- names, the first one of each user (BACKUP-7) is "My Backups", every other one is described by its name
ALTER TABLE restic_repositories ADD COLUMN description VARCHAR(255) NOT NULL DEFAULT '';
UPDATE restic_repositories SET description = CASE WHEN name = 'my-backups' THEN 'My Backups' ELSE name END;
