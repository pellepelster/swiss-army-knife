-- the initial schema. sqlite takes checks and foreign keys only when a table is created, so unique
-- constraints over several columns are unique indexes; the unique indexes over live rows are partial.
-- Every table has created_at, updated_at (null until changed) and deleted_at (null until deleted);
-- no row is deleted for real. Every table has owner (MODEL-5, SchemaTest): the e-mail address of a
-- user, admin for the password login and sakadmin, or system for what the backend creates.
-- The tables as liquibase made them until V10 (TEXT for a timestamp, PK_<table> for the primary key),
-- so that a new database is the same as one of then

-- the buckets of the s3 api, each with the key pair that may access it. A deleted bucket stays as a
-- row with deleted_at set, its name stays taken until it is purged
CREATE TABLE s3_buckets (
  name VARCHAR(63) NOT NULL,
  access_key_id VARCHAR(20) NOT NULL,
  secret_access_key VARCHAR(40) NOT NULL,
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_S3_BUCKETS PRIMARY KEY (name),
  CONSTRAINT s3_buckets_access_key_id_key UNIQUE (access_key_id)
);

-- the domains a bucket is served at, in their order; redirects to the bucket lead to the first. A
-- live domain belongs to one bucket at most
CREATE TABLE s3_buckets_domains (
  id INTEGER NOT NULL,
  domain VARCHAR(253) NOT NULL,
  bucket_name VARCHAR(63) NOT NULL,
  position INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_S3_BUCKETS_DOMAINS PRIMARY KEY (id),
  CONSTRAINT s3_buckets_domains_bucket_name_fkey FOREIGN KEY (bucket_name) REFERENCES s3_buckets (name)
);
CREATE UNIQUE INDEX s3_buckets_domains_domain_key ON s3_buckets_domains (domain) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX s3_buckets_domains_position_key ON s3_buckets_domains (bucket_name, position) WHERE deleted_at IS NULL;

-- the domains caddy redirects, to an external domain or to a bucket; exactly the column of the target
-- type is set. A domain has one live redirect at most
CREATE TABLE redirects (
  id INTEGER NOT NULL PRIMARY KEY,
  source_domain VARCHAR(253) NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_domain VARCHAR(253),
  target_bucket VARCHAR(63),
  owner VARCHAR(254) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  deleted_at TIMESTAMP,
  CONSTRAINT redirects_target_bucket_fkey FOREIGN KEY (target_bucket) REFERENCES s3_buckets (name),
  CONSTRAINT redirects_target_check CHECK (
    (target_type = 'DOMAIN' AND target_domain IS NOT NULL AND target_bucket IS NULL)
    OR (target_type = 'BUCKET' AND target_bucket IS NOT NULL AND target_domain IS NULL)
  )
);
CREATE UNIQUE INDEX redirects_source_domain_key ON redirects (source_domain) WHERE deleted_at IS NULL;

-- the labels of a user, each application links them in a table of its own; only the owner sees them.
-- The name is unique among the live labels of an owner
CREATE TABLE labels (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(64) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_LABELS PRIMARY KEY (id)
);
CREATE UNIQUE INDEX labels_owner_name_key ON labels (owner, name) WHERE deleted_at IS NULL;

-- the files of the file storage, one row an upload; the content is in the data dir under its sha-256
-- (lower case hex), the same content stored once. discovered is null until the text in it is
-- discovered
CREATE TABLE files (
  id INTEGER NOT NULL,
  sha256 CHAR(64) NOT NULL,
  content_type VARCHAR(127) NOT NULL,
  size BIGINT NOT NULL,
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  discovered VARCHAR(100000),
  CONSTRAINT PK_FILES PRIMARY KEY (id)
);

-- the lists of the notes: every note is in one, a user without a list gets "Default" when they sign
-- in (NOTES-1). A shared list is one of all users (LIST-2). The name is unique among the live lists of
-- an owner
CREATE TABLE note_lists (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(64) NOT NULL,
  shared BOOLEAN NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_NOTE_LISTS PRIMARY KEY (id)
);
CREATE UNIQUE INDEX note_lists_owner_name_key ON note_lists (owner, name) WHERE deleted_at IS NULL;

-- the notes, each of the user that created it and in one of their lists
CREATE TABLE notes (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  list_id INTEGER NOT NULL,
  text VARCHAR(10000) NOT NULL,
  color VARCHAR(16) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_NOTES PRIMARY KEY (id),
  CONSTRAINT notes_list_id_fkey FOREIGN KEY (list_id) REFERENCES note_lists (id)
);
CREATE INDEX notes_owner_idx ON notes (owner);

-- the labels of a note; a link removed, also of a deleted note or label, stays with deleted_at set. A
-- label has one live link to a note at most
CREATE TABLE notes_labels (
  id INTEGER NOT NULL,
  note_id INTEGER NOT NULL,
  label_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_NOTES_LABELS PRIMARY KEY (id),
  CONSTRAINT notes_labels_note_id_fkey FOREIGN KEY (note_id) REFERENCES notes (id),
  CONSTRAINT notes_labels_label_id_fkey FOREIGN KEY (label_id) REFERENCES labels (id)
);
CREATE UNIQUE INDEX notes_labels_label_key ON notes_labels (note_id, label_id) WHERE deleted_at IS NULL;
-- the live links of a label, what detaching a deleted label reads (NoteRepository.detachLabel)
CREATE INDEX notes_labels_label_id_idx ON notes_labels (label_id) WHERE deleted_at IS NULL;

-- the files of a note in their order, photos and recordings told apart by the type of the file. A
-- link removed stays with deleted_at set; a file has one live link to a note at most
CREATE TABLE notes_files (
  id INTEGER NOT NULL,
  note_id INTEGER NOT NULL,
  file_id INTEGER NOT NULL,
  position INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_NOTES_FILES PRIMARY KEY (id),
  CONSTRAINT notes_files_note_id_fkey FOREIGN KEY (note_id) REFERENCES notes (id),
  CONSTRAINT notes_files_file_id_fkey FOREIGN KEY (file_id) REFERENCES files (id)
);
CREATE UNIQUE INDEX notes_files_file_key ON notes_files (note_id, file_id) WHERE deleted_at IS NULL;

-- the settings of the backend, by name and scope (e.g. 'system', SettingScope in sak-backend). Some
-- are secrets (e.g. AUTH_SIGNING_KEY), stored plain: the whole database is to be encrypted. A
-- replaced setting stays as deleted; one live setting per name and scope
CREATE TABLE settings (
  id INTEGER NOT NULL,
  name VARCHAR(64) NOT NULL,
  value VARCHAR(16384) NOT NULL,
  scope VARCHAR(255) NOT NULL,
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_SETTINGS PRIMARY KEY (id)
);
CREATE UNIQUE INDEX settings_name_scope_key ON settings (name, scope) WHERE deleted_at IS NULL;

-- the grocery lists: every item is on one, the user creates them (LIST-1); a shared list is one of
-- all users (LIST-2). The name is unique among the live lists of an owner. The icon is one emoji, none
-- shows the first letter of the name
CREATE TABLE grocery_lists (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(64) NOT NULL,
  shared BOOLEAN NOT NULL,
  icon VARCHAR(32),
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_GROCERY_LISTS PRIMARY KEY (id)
);
CREATE UNIQUE INDEX grocery_lists_owner_name_key ON grocery_lists (owner, name) WHERE deleted_at IS NULL;

-- the categories of a list (GROCERY-13): a name, an icon (one emoji or none) and the place of their
-- group. name_key is the name normalized (groceryNameKey), unique among the live categories of a list
CREATE TABLE grocery_categories (
  id INTEGER NOT NULL PRIMARY KEY,
  list_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(32) NOT NULL,
  name_key VARCHAR(32) NOT NULL,
  icon VARCHAR(32),
  position INTEGER NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  deleted_at TIMESTAMP,
  CONSTRAINT grocery_categories_list_id_fkey FOREIGN KEY (list_id) REFERENCES grocery_lists (id)
);
CREATE INDEX grocery_categories_list_id_idx ON grocery_categories (list_id);
CREATE UNIQUE INDEX grocery_categories_list_name_key ON grocery_categories (list_id, name_key) WHERE deleted_at IS NULL;

-- the items of the grocery lists; owner is who added it, on a shared list maybe not the owner of the
-- list. An item is to buy until checked (GROCERY-3); checked, it stays as the history the suggestions
-- are made of (GROCERY-11). A forgotten suggestion deletes the checked items of its name. name_key as
-- in grocery_categories. An amount is positive, a unit needs one; the repository checks that the
-- category is one of the list (GROCERY-13); the photo is a file of the file storage (GROCERY-12)
CREATE TABLE grocery_items (
  id INTEGER NOT NULL PRIMARY KEY,
  list_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(128) NOT NULL,
  name_key VARCHAR(128) NOT NULL,
  amount DECIMAL(10, 3),
  unit VARCHAR(16),
  category_id INTEGER,
  photo_file_id INTEGER,
  checked_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  deleted_at TIMESTAMP,
  CONSTRAINT grocery_items_list_id_fkey FOREIGN KEY (list_id) REFERENCES grocery_lists (id),
  CONSTRAINT grocery_items_category_id_fkey FOREIGN KEY (category_id) REFERENCES grocery_categories (id),
  CONSTRAINT grocery_items_photo_file_id_fkey FOREIGN KEY (photo_file_id) REFERENCES files (id),
  CONSTRAINT grocery_items_amount_check CHECK (amount IS NULL OR amount > 0),
  CONSTRAINT grocery_items_unit_check CHECK (unit IS NULL OR amount IS NOT NULL)
);
-- the items of a list by name, what the suggestions group
CREATE INDEX grocery_items_list_name_idx ON grocery_items (list_id, name_key);
-- the items to buy of a list, what a list counts and the suggestions leave out, without its history
CREATE INDEX grocery_items_to_buy_idx ON grocery_items (list_id) WHERE deleted_at IS NULL AND checked_at IS NULL;

-- the users an admin adds by e-mail address, with an optional name; they sign in by link and have the
-- role user. The admins of the configuration are no rows. One live row per address
CREATE TABLE users (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  email VARCHAR(254) NOT NULL,
  name VARCHAR(64),
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_USERS PRIMARY KEY (id)
);
CREATE UNIQUE INDEX users_email_key ON users (email) WHERE deleted_at IS NULL;
