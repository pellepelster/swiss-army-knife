-- the repositories of restic the instance keeps (docs/development/architecture/components/BACKUP.md),
-- the same rules as in V1__initial.sql. Their content is on the data volume, below
-- restic/<resource_id> (MODEL-8); no column holds the password of a repository (BACKUP-3); the backups
-- with the paths of one computer each (BACKUP-5, BACKUP-6)

-- a repository (BACKUP-1) and its token, the only login of its REST backend (BACKUP-2); resource_id is
-- the directory of the repository on the data volume (MODEL-8)
CREATE TABLE restic_repositories (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(64) NOT NULL,
  token VARCHAR(64) NOT NULL,
  resource_id CHAR(12) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_RESTIC_REPOSITORIES PRIMARY KEY (id)
);
CREATE UNIQUE INDEX restic_repositories_owner_name_key ON restic_repositories (owner, name) WHERE deleted_at IS NULL;
-- over the deleted rows too: a resource id is never given to another repository (MODEL-8)
CREATE UNIQUE INDEX restic_repositories_resource_id_key ON restic_repositories (resource_id);

-- a backup (BACKUP-5): a repository of its owner and the paths of one computer, named by its host
-- name. keep_* is how many last, daily, weekly, monthly and yearly snapshots a run keeps, none keeps
-- them all (BACKUP-8)
CREATE TABLE restic_backup_plans (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(64) NOT NULL,
  repository_id INTEGER NOT NULL,
  host VARCHAR(253) NOT NULL,
  keep_last INTEGER,
  keep_daily INTEGER,
  keep_weekly INTEGER,
  keep_monthly INTEGER,
  keep_yearly INTEGER,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_RESTIC_BACKUP_PLANS PRIMARY KEY (id),
  CONSTRAINT restic_backup_plans_repository_id_fkey FOREIGN KEY (repository_id) REFERENCES restic_repositories (id)
);
CREATE UNIQUE INDEX restic_backup_plans_owner_name_key ON restic_backup_plans (owner, name) WHERE deleted_at IS NULL;

-- the paths of a backup in their order (BACKUP-6)
CREATE TABLE restic_backup_plan_paths (
  id INTEGER NOT NULL,
  plan_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  path VARCHAR(4096) NOT NULL,
  position INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_RESTIC_BACKUP_PLAN_PATHS PRIMARY KEY (id),
  CONSTRAINT restic_backup_plan_paths_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES restic_backup_plans (id)
);
CREATE INDEX restic_backup_plan_paths_plan_id_idx ON restic_backup_plan_paths (plan_id) WHERE deleted_at IS NULL;
