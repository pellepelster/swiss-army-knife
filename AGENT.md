# Agent guidelines

## Repositories and services

Every entity of the controller's database (`sak-model`) has an `<entity>Repository` and an
`<entity>Service`, both in the entity's package, e.g. `io.pelle.sak.model.s3.S3BucketRepository`
and `io.pelle.sak.model.s3.S3BucketService`.

- All database access happens in the `<entity>Repository`, nowhere else. It is the only class
  that touches jOOQ and the generated tables of its entity.
- The `<entity>Repository` is used by its `<entity>Service` only. It is `internal` to
  `sak-model`, so the compiler rejects any other use from outside the module.
- Everything outside `sak-model` (Ktor routes, S3 access callbacks, the controller's wiring)
  talks to the `<entity>Service` only, never to a repository or to `SakDatabase.dsl`.
- Business rules (validation, key generation, reserved names, results like
  `CreateBucketResult`) live in the service. The repository only reads and writes rows.
- The service is handed the `SakDatabase` and creates its repository; tests reach the internal
  constructor to pass their own repository, clock or other collaborators.
