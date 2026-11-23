-- the tables of the todo app (docs/development/architecture/components/TODO.md), the same rules as in
-- V1__initial.sql: created_at, updated_at, deleted_at and owner on every table, no row is ever deleted
-- for real, the unique indexes over live rows partial

-- the todo lists, as grocery_lists (LIST-1, LIST-2)
CREATE TABLE todo_lists (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  name VARCHAR(64) NOT NULL,
  shared BOOLEAN NOT NULL,
  icon VARCHAR(32),
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_TODO_LISTS PRIMARY KEY (id)
);
CREATE UNIQUE INDEX todo_lists_owner_name_key ON todo_lists (owner, name) WHERE deleted_at IS NULL;

-- the tasks; owner is who added it, on a shared list maybe not the owner of the list. due_date is the
-- day it is due, null for none (TODO-4); sqlite stores it as text, the check lets only a real day as
-- yyyy-mm-dd pass (date() of anything else is null or another text). priority is high, normal or low
-- (TODO-5). done_at is null while it is open (TODO-3); a purge deletes the done tasks of a list
-- (TODO-6), nothing of them is kept or learned
CREATE TABLE todo_items (
  id INTEGER NOT NULL PRIMARY KEY,
  list_id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  title VARCHAR(256) NOT NULL,
  due_date DATE,
  priority VARCHAR(8) NOT NULL DEFAULT 'normal',
  done_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  deleted_at TIMESTAMP,
  CONSTRAINT todo_items_list_id_fkey FOREIGN KEY (list_id) REFERENCES todo_lists (id),
  CONSTRAINT todo_items_priority_check CHECK (priority IN ('high', 'normal', 'low')),
  CONSTRAINT todo_items_due_date_check CHECK (due_date IS NULL OR (date(due_date) IS NOT NULL AND due_date = date(due_date)))
);
-- the tasks of a list, what a list shows
CREATE INDEX todo_items_list_id_idx ON todo_items (list_id) WHERE deleted_at IS NULL;
-- the open tasks of a list, what a list counts
CREATE INDEX todo_items_open_idx ON todo_items (list_id) WHERE deleted_at IS NULL AND done_at IS NULL;
