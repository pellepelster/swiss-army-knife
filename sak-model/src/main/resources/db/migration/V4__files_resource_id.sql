-- the files get a resource id (MODEL-8): their content is on the data volume below files/<resource_id>,
-- no longer below its sha-256. The rows of every file get one, the deleted ones included; the backend
-- of that release moved their content when it started. sha256, which named where the content was,
-- goes with V6.
-- sqlite can not add a column without a default to a table that has rows, and other tables refer to
-- files, so it is not rebuilt: the default is only there for the rows that are now, the repository
-- sets the resource id of every new row. 6 random bytes in hex are 12 characters of a-z0-9; should
-- two rows draw the same, the unique index fails, the migration is rolled back and the next start
-- draws again
ALTER TABLE files ADD COLUMN resource_id CHAR(12) NOT NULL DEFAULT '';
UPDATE files SET resource_id = lower(hex(randomblob(6)));
-- over the deleted rows too: a resource id is never given to another file (MODEL-8)
CREATE UNIQUE INDEX files_resource_id_key ON files (resource_id);
