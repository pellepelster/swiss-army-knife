-- the lists of the notes get the icon every list of a list-based app has (LIST-1), so that the notes work
-- on their lists with the code of the other list-based apps. No list of the notes is shown to a user
-- yet (NOTES-1), so no list has an icon. A column that may be null is added to a table that has rows as
-- it is; the table is not made again, which would leave the foreign key of the notes at a copy
ALTER TABLE note_lists ADD COLUMN icon VARCHAR(32);
