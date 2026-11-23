-- the buckets get a resource id (MODEL-8): their objects are on the data volume below s3/<resource_id>,
-- no longer below buckets/<name>. The rows of every bucket get one, the deleted ones included.
-- objects_at_name told the buckets whose objects were still below their name: every bucket that was
-- there then, until the backend of that release copied its objects when it started; it goes with V6.
-- As for the files (V4): sqlite adds no column without a default to a table that has rows, and
-- s3_buckets_domains refers to the table, so it is not rebuilt. The defaults are only there for the
-- rows that are now, the repository sets the resource id of every new row. Should two rows draw the
-- same resource id, the unique index fails, the migration is rolled back and the next start draws again
ALTER TABLE s3_buckets ADD COLUMN resource_id CHAR(12) NOT NULL DEFAULT '';
UPDATE s3_buckets SET resource_id = lower(hex(randomblob(6)));
-- over the deleted rows too: a resource id is never given to another bucket (MODEL-8)
CREATE UNIQUE INDEX s3_buckets_resource_id_key ON s3_buckets (resource_id);
ALTER TABLE s3_buckets ADD COLUMN objects_at_name BOOLEAN NOT NULL DEFAULT 0;
UPDATE s3_buckets SET objects_at_name = 1;
