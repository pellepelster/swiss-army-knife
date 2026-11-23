-- the columns that only the copies to the resource ids read go (MODEL-8): files.sha256 named where the
-- content of a file was before V4, s3_buckets.objects_at_name the buckets whose objects were still
-- below their name after V5. The backend of the release between copied both when it started; an
-- instance must have started that release once before this migration runs
ALTER TABLE files DROP COLUMN sha256;
ALTER TABLE s3_buckets DROP COLUMN objects_at_name;
