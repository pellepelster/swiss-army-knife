---
title: "Model"
description: "How the backend keeps its data: one database, soft deletes, the time and the owner of every row, the access checked in the repository, and the data outside the database."
weight: 15
---

# Model

Adds to [SYSTEM.md](SYSTEM.md). The rules of this document have the prefix `MODEL`.

- `MODEL-1` **One database.** The backend keeps its data in one SQLite database on the data volume.
- `MODEL-2` **The database is only in `sak-model`.** `sak-model` holds the schema, the entities and their
  repositories, nothing else. Only the repository of an entity, named `<entity>Repository`, and what the repositories
  share inside `sak-model` touch the database, jOOQ and its generated tables. No other module does.
- `MODEL-3` **Soft delete only.** No row is ever deleted for real. A delete marks the row as deleted; every read skips
  such rows. A value that must be unique is unique among the live rows only. A deleted bucket keeps its name taken
  until it is purged.
- `MODEL-4` **Every row records its time.** Every table, link tables included, has `created_at`, `updated_at` and
  `deleted_at`, set by the repository from one clock. `created_at` is set when the row is inserted and is never null.
  `updated_at` is null until the row really changes: an insert never sets it, and a write of the values the row
  already has does not set it. `deleted_at` is null until the row is deleted.
- `MODEL-5` **Every row records its owner.** Every table, link tables included, has a column `owner` that is never null.
  It holds who created the row: the e-mail address of the user, `admin` for the admin of the password login without an
  address, or `system` when the backend created it on its own, e.g. a setting it generates. A request of sakadmin acts
  as the admin of the password login, so what it creates is theirs. An insert takes the owner from its caller: a route
  from the user of the request, the backend on its own from `SYSTEM_OWNER`. The owner is set once, when the row is
  inserted, and no update changes it.
- `MODEL-6` **Access is checked in the repository.** A repository method on the data of a user takes the user, and
  the check of who sees or changes the row is in its sql, so a caller can not forget it and no other change comes
  between the check and the read or the change. The service checks the values, never the access. The exceptions are
  the data of the whole deployment, which only the role `admin` reaches (the buckets, the redirects, the settings),
  and the work the backend does on its own (e.g. the text found in a file).
- `MODEL-7` **Local binary data only through `FilesystemLocalStorage`.** All binary data the backend stores on its
  data volume (the files of the file storage, their thumbnails, the objects of the buckets, the repositories of
  restic kept there) is written and read through `FilesystemLocalStorage` (`sak-storage`); no other code writes
  binary data to disk. The one exception: caddy serves the objects of a bucket read only from their directory on
  disk, which `sak-s3` hands out (`objectsDirectory`). The rule is for the local storage only: what the backend keeps
  in the S3 storage (`BACKUP-1`) is not on disk and goes through the client of `S3StorageManager`.
- `MODEL-8` **Data outside the database has a resource id.** A row whose data is kept outside the database (on the data
  volume) has a `resource_id`: 12 random characters `a-z0-9`, set once when the row is inserted, unique among all rows
  of its table, deleted rows included. The paths of that data are made only from the resource id, never from a name, an
  id of the database or the content.
- `MODEL-9` **A repository takes values and answers rows.** No method that a service calls takes a time or an entity
  that was built outside the repository: an insert and an update take the values of the fields and answer the row as
  it is stored. A write that can fail in one way answers the row or nothing, a delete the row it deleted; a write
  that can fail in more than one way answers a result that names each way. No write answers only yes or no. The
  service hands its clock to the repository it makes, so both use one clock (`MODEL-4`).
- `MODEL-10` **A soft delete keeps the data outside the database.** A delete of a row whose data is kept outside the
  database (`MODEL-8`), e.g. the files of a repository of restic or the objects of a bucket, leaves that data where it
  is; nobody reaches it from then on. The data is removed only when the row is purged.
