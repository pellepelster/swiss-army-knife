-- every timestamp is TEXT and every primary key is the constraint PK_<table>, as in the tables V1
-- took over from liquibase (DM-3, SchemaTest). The four tables written in plain sql had their
-- timestamps as TIMESTAMP, which sqlite gives the numeric affinity, and their primary key inline; the
-- values were text in both, so the rows stay as they are. sqlite changes no column type: each table
-- is made again. Its rows are kept in a copy, the table is dropped and made again under its name, so
-- the foreign keys that refer to it stay at it, and the rows are put back with their ids; its indexes
-- went with the table and are made again. grocery_items refers to grocery_categories, and the foreign
-- keys are enforced: they are checked at the end of this transaction (defer_foreign_keys, back to its
-- default with the commit)
PRAGMA defer_foreign_keys = ON;

CREATE TEMP TABLE redirects_copy AS SELECT * FROM redirects;
CREATE TEMP TABLE grocery_categories_copy AS SELECT * FROM grocery_categories;
CREATE TEMP TABLE grocery_items_copy AS SELECT * FROM grocery_items;
CREATE TEMP TABLE todo_items_copy AS SELECT * FROM todo_items;
DROP TABLE redirects;
DROP TABLE grocery_items;
DROP TABLE grocery_categories;
DROP TABLE todo_items;

CREATE TABLE redirects (
  id INTEGER NOT NULL,
  source_domain VARCHAR(253) NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_domain VARCHAR(253),
  target_bucket VARCHAR(63),
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_REDIRECTS PRIMARY KEY (id),
  CONSTRAINT redirects_target_bucket_fkey FOREIGN KEY (target_bucket) REFERENCES s3_buckets (name),
  CONSTRAINT redirects_target_check CHECK (
    (target_type = 'DOMAIN' AND target_domain IS NOT NULL AND target_bucket IS NULL)
    OR (target_type = 'BUCKET' AND target_bucket IS NOT NULL AND target_domain IS NULL)
  )
);
INSERT INTO redirects (id, source_domain, target_type, target_domain, target_bucket, owner, created_at, updated_at, deleted_at)
SELECT id, source_domain, target_type, target_domain, target_bucket, owner, created_at, updated_at, deleted_at FROM redirects_copy;
CREATE UNIQUE INDEX redirects_source_domain_key ON redirects (source_domain) WHERE deleted_at IS NULL;

CREATE TABLE grocery_categories (
  id INTEGER NOT NULL,
  list_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(32) NOT NULL,
  name_key VARCHAR(32) NOT NULL,
  icon VARCHAR(32),
  position INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_GROCERY_CATEGORIES PRIMARY KEY (id),
  CONSTRAINT grocery_categories_list_id_fkey FOREIGN KEY (list_id) REFERENCES grocery_lists (id)
);
INSERT INTO grocery_categories (id, list_id, owner, name, name_key, icon, position, created_at, updated_at, deleted_at)
SELECT id, list_id, owner, name, name_key, icon, position, created_at, updated_at, deleted_at FROM grocery_categories_copy;
CREATE INDEX grocery_categories_list_id_idx ON grocery_categories (list_id);
CREATE UNIQUE INDEX grocery_categories_list_name_key ON grocery_categories (list_id, name_key) WHERE deleted_at IS NULL;

CREATE TABLE grocery_items (
  id INTEGER NOT NULL,
  list_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(128) NOT NULL,
  name_key VARCHAR(128) NOT NULL,
  amount DECIMAL(10, 3),
  unit VARCHAR(16),
  category_id INTEGER,
  photo_file_id INTEGER,
  checked_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_GROCERY_ITEMS PRIMARY KEY (id),
  CONSTRAINT grocery_items_list_id_fkey FOREIGN KEY (list_id) REFERENCES grocery_lists (id),
  CONSTRAINT grocery_items_category_id_fkey FOREIGN KEY (category_id) REFERENCES grocery_categories (id),
  CONSTRAINT grocery_items_photo_file_id_fkey FOREIGN KEY (photo_file_id) REFERENCES files (id),
  CONSTRAINT grocery_items_amount_check CHECK (amount IS NULL OR amount > 0),
  CONSTRAINT grocery_items_unit_check CHECK (unit IS NULL OR amount IS NOT NULL)
);
INSERT INTO grocery_items (id, list_id, owner, name, name_key, amount, unit, category_id, photo_file_id, checked_at, created_at, updated_at, deleted_at)
SELECT id, list_id, owner, name, name_key, amount, unit, category_id, photo_file_id, checked_at, created_at, updated_at, deleted_at FROM grocery_items_copy;
CREATE INDEX grocery_items_list_name_idx ON grocery_items (list_id, name_key);
CREATE INDEX grocery_items_to_buy_idx ON grocery_items (list_id) WHERE deleted_at IS NULL AND checked_at IS NULL;

CREATE TABLE todo_items (
  id INTEGER NOT NULL,
  list_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  title VARCHAR(256) NOT NULL,
  due_date DATE,
  priority VARCHAR(8) NOT NULL DEFAULT 'normal',
  done_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_TODO_ITEMS PRIMARY KEY (id),
  CONSTRAINT todo_items_list_id_fkey FOREIGN KEY (list_id) REFERENCES todo_lists (id),
  CONSTRAINT todo_items_priority_check CHECK (priority IN ('high', 'normal', 'low')),
  CONSTRAINT todo_items_due_date_check CHECK (due_date IS NULL OR (date(due_date) IS NOT NULL AND due_date = date(due_date)))
);
INSERT INTO todo_items (id, list_id, owner, title, due_date, priority, done_at, created_at, updated_at, deleted_at)
SELECT id, list_id, owner, title, due_date, priority, done_at, created_at, updated_at, deleted_at FROM todo_items_copy;
CREATE INDEX todo_items_list_id_idx ON todo_items (list_id) WHERE deleted_at IS NULL;
CREATE INDEX todo_items_open_idx ON todo_items (list_id) WHERE deleted_at IS NULL AND done_at IS NULL;

DROP TABLE redirects_copy;
DROP TABLE grocery_categories_copy;
DROP TABLE grocery_items_copy;
DROP TABLE todo_items_copy;
