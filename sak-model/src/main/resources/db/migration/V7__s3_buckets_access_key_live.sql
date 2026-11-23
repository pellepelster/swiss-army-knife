-- the access key id of a bucket is unique among the live buckets only (MODEL-3): a deleted bucket keeps
-- its name taken, nothing else. The unique constraint of V1 is part of the table, and sqlite drops no
-- constraint: the table is made again without it, with a partial unique index in its place.
-- The domains and the redirects refer to s3_buckets, and the foreign keys are enforced. They are
-- checked at the end of this transaction (defer_foreign_keys, back to its default with the commit):
-- the rows are kept in a copy, the table is dropped and made again under its name, and the rows put
-- back give every domain and redirect its bucket again. The index of the resource ids went with the
-- table and is made again
PRAGMA defer_foreign_keys = ON;
CREATE TABLE s3_buckets_copy AS SELECT * FROM s3_buckets;
DROP TABLE s3_buckets;
CREATE TABLE s3_buckets (
  name VARCHAR(63) NOT NULL,
  access_key_id VARCHAR(20) NOT NULL,
  secret_access_key VARCHAR(40) NOT NULL,
  owner VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  resource_id CHAR(12) NOT NULL DEFAULT '',
  CONSTRAINT PK_S3_BUCKETS PRIMARY KEY (name)
);
INSERT INTO s3_buckets (name, access_key_id, secret_access_key, owner, created_at, updated_at, deleted_at, resource_id)
SELECT name, access_key_id, secret_access_key, owner, created_at, updated_at, deleted_at, resource_id FROM s3_buckets_copy;
DROP TABLE s3_buckets_copy;
CREATE UNIQUE INDEX s3_buckets_access_key_id_key ON s3_buckets (access_key_id) WHERE deleted_at IS NULL;
-- over the deleted rows too: a resource id is never given to another bucket (MODEL-8)
CREATE UNIQUE INDEX s3_buckets_resource_id_key ON s3_buckets (resource_id);
