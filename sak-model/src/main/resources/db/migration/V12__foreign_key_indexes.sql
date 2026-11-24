-- every foreign key has an index that starts with its column (DM-4, SchemaTest): a read of the rows that
-- refer to a row, e.g. the items of a category that is deleted, finds them without reading the whole
-- table. Over all rows, the deleted ones too, as the foreign keys refer to them as well
CREATE INDEX notes_list_id_idx ON notes (list_id);
CREATE INDEX notes_files_file_id_idx ON notes_files (file_id);
CREATE INDEX grocery_items_category_id_idx ON grocery_items (category_id);
CREATE INDEX grocery_items_photo_file_id_idx ON grocery_items (photo_file_id);
CREATE INDEX restic_backup_plans_repository_id_idx ON restic_backup_plans (repository_id);
CREATE INDEX redirects_target_bucket_idx ON redirects (target_bucket);
