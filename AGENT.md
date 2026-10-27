# Agent guidelines

## Persistence (`sak-model`)

- Every table, link tables included, has `created_at`, `updated_at` and `deleted_at` (`SchemaTest`
  checks it):
  - `created_at` is set when the row is inserted, never null;
  - `updated_at` is null until the row really changes, an insert never sets it;
  - `deleted_at` is null until the row is deleted, a delete sets nothing else.
- Every entity supports soft delete only, no row is ever deleted for real: every read skips the
  rows with `deleted_at` set, a value unique among the live rows has a partial unique index
  (`WHERE deleted_at IS NULL`). A deleted bucket keeps its name taken until it is purged.
- The repository sets them, from the `Clock` it is constructed with (the service hands its own on);
  no repository method takes a time or an entity built outside, inserts and updates take the
  fields and answer the row as it is stored.
- Every secret (a bucket's secret key, the signing key of the login, …) lives in the `secrets`
  table and nowhere else, no other table and no file holds one. `SecretService` (sak-backend) is
  the only way to them: it encrypts with AES-256-GCM under the keys of `sak.secret_keys`
  (versioned, the newest encrypts, older ones are encrypted anew on start; terraform creates them),
  authenticates the type and scope with each value and hands them out as `Secret`, whose
  `toString()` never shows the value. Configuration and value classes holding a secret override
  `toString()` the same way.
- `sak-model` holds the schema, the entities and their repositories, nothing else. Database access
  only in `<entity>Repository`, the only class touching jOOQ and its generated tables;
  `SakDatabase.dsl` is `internal`, the compiler keeps it in the module.

## Services (`sak-backend`)

- All business logic lives in `<entity>Service` in `io.pelle.sak.backend.<app>`, next to the
  app's routes. A service talks only to other services and its own repository, a repository is
  used only by its service; routes use services, never repositories.

## API (`/api`)

- Routes install `ApiErrors` (`sak-api`, `io.pelle.sak.api`) next to content negotiation.
- Errors (400 validation, 409 conflict, malformed body, …) answer
  `{"errors": [{"message": "..."}]}` (`call.respondErrors`), one readable sentence per problem.
- Lists are paginated: `{"items": [...], "pagination": {"offset", "limit", "total"}}`, asked for
  with the `offset` and `limit` query parameters; `limit` is 50 by default and at most 100
  (`MAX_LIMIT`, `sak-api`).
- Bodies received are `<name>Request`, bodies answered `<name>Response`, also every class nested
  in them (e.g. `PageResponse`, `SiteMetricsResponse`); a domain class is mapped to its response
  class, it never crosses the api itself.
- Routes of an app in `io.pelle.sak.backend.<app>`, its request/response classes in
  `io.pelle.sak.backend.<app>.model`, one class per file named like the class. The other modules
  answering on `/api` keep theirs the same way in `io.pelle.sak.<module>.model` (`sak-api`,
  `sak-auth`, `sak-logs`).

## UI (`sak-frontend`)

- Every entity has a paginated list view leading to a detail view; creating is started from the
  list's toolbar, all actions on an entity sit in a toolbar at its top right.
- Every list is paginated with the shared `app-pagination` (`shared/pagination.ts`), `PAGE_SIZE`
  items a page; an entity list keeps its page in the `offset` query parameter. Only choices and
  suggestions (e.g. a select, a datalist) load a whole list, page by page with `fetchAll`
  (`shared/api.ts`).
- Detail view: overview panel on top (name, created, updated, key facts), tabs below, one child
  route each, the first and default one "Configuration".
- No modals: create on `/<entities>/create`, edit on `/<entities>/<id>/edit`, all editable
  attributes at once, saved or cancelled.
- Bootstrap everywhere.
- Copying to the clipboard is `app-copy-button` (`shared/copy-button.ts`), never written anew.
- The apps follow the browser's `prefers-color-scheme` with no switch of their own (css custom
  properties in `src/styles.scss`).
- Dates and times in the browser's locale via the `localDateTime` pipe
  (`shared/local-date-time.pipe.ts`), never angular's `DatePipe` or a fixed format.
- Async code, the same everywhere:
  - State lives in signals, the templates read them; a store holds what a page shares with its
    tabs (`@Injectable()` provided by the page, e.g. `BucketStore`) or what the whole app shares
    (`providedIn: 'root'`, e.g. `NotesStore`).
  - Observables (`HttpClient`, `timer`) are subscribed with
    `.pipe(takeUntilDestroyed(this.destroyRef))`, `destroyRef = inject(DestroyRef)` the first
    field; no `Subscription` collections, no `DestroyRef.onDestroy(() => ….unsubscribe())`.
  - A load that replaces the one before (another page, polling) keeps its `Subscription` in one
    field and unsubscribes it before starting the next.
  - A load following inputs or signals runs in an `effect`, the load itself `untracked`.
  - An action whose outcome the caller awaits (e.g. to focus a created note) returns a `Promise`
    with `firstValueFrom`; everything else subscribes.

## Privacy

- Nothing is loaded from outside `<root_domain>` (no cdn, font service, analytics or other third
  party); everything is bundled (e.g. `@fontsource`, `swagger-ui-dist`) or served by the backend.
  The content security policy (`contentSecurityPolicy`) allows only the page's own origin and
  `api.<root_domain>`, it is never widened beyond `<root_domain>`.
- User data leaves the backend only on the user's explicit request (e.g. text discovery) and only to
  providers that do not collect it (openrouter `provider.data_collection: deny`).
