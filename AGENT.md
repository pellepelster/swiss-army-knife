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

## API

Every route under `/api` answers alike; the types and helpers are in `sak-api`
(`io.pelle.sak.api`), routes install its `ApiErrors` plugin next to content negotiation.

- Validation errors are answered with status 400 and always the same body:
  `{"errors": [{"message": "..."}, ...]}` (`ErrorResponse`, `call.respondErrors(...)`), one
  entry per problem, the message a sentence a user can read. A malformed or unreadable body
  gets the same answer through `ApiErrors`. Conflicts (409) and other answers that tell what is
  wrong with a request use the same body.
- Every list endpoint is paginated alike: the query parameters `offset` (0 by default) and
  `limit` (`DEFAULT_LIMIT` by default, at most `MAX_LIMIT`), parsed with
  `call.pageRequest()`, and the answer
  `{"items": [...], "pagination": {"offset": 0, "limit": 50, "total": 123}}` (`Page`), where
  `total` counts the whole list. Invalid `offset` or `limit` are validation errors.
- The openapi description documents both: `parameters { pagination() }` for the page and
  `responses { errors(status, description) }` for every error answer.
