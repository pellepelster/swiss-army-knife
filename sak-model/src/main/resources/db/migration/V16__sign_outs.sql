-- the sign outs everywhere of an address (SEC-4): a session or a code issued before the latest one
-- counts for nothing. A row for each sign out, the admins of the configuration too, who are no users;
-- the owner is who signed the address out, the user or an admin
CREATE TABLE sign_outs (
  id INTEGER NOT NULL,
  owner VARCHAR(254) NOT NULL,
  email VARCHAR(254) NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  deleted_at TEXT,
  CONSTRAINT PK_SIGN_OUTS PRIMARY KEY (id)
);
CREATE INDEX sign_outs_email ON sign_outs (email);
