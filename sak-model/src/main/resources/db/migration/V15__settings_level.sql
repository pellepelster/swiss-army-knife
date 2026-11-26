-- the column of the level of a setting (SHARED-4): null for a setting of the instance, the address of
-- the user for a setting of a user. It was named user; sqlite renames it in the index settings_key too
ALTER TABLE settings RENAME COLUMN user TO level;
