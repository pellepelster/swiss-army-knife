-- a setting is of the instance (user null) or of one user, in the same scopes (MODEL-6): once for each
-- scope and name for the instance, once for each user. sqlite adds a column that may be null without
-- making the table again
ALTER TABLE settings ADD COLUMN user VARCHAR(254);
DROP INDEX settings_name_scope_key;
CREATE UNIQUE INDEX settings_key ON settings (scope, name, coalesce(user, '')) WHERE deleted_at IS NULL;

-- the AI of an app is no switch of the admins any more but one of each user: what the admins set
-- stays as deleted
UPDATE settings SET deleted_at = strftime('%Y-%m-%d %H:%M:%f', 'now')
WHERE scope IN ('notes', 'grocery') AND name = 'ai' AND user IS NULL AND deleted_at IS NULL;
