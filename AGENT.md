# Agent guidelines

## Persistence (`sak-model`)

- Every entity has `created`, `updated` and `deleted` (soft delete) timestamps.
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
- Bodies received are `<name>Request`, bodies answered `<name>Response`.
- Routes of an app in `io.pelle.sak.backend.<app>`, its request/response classes in
  `io.pelle.sak.backend.<app>.model`, one class per file named like the class.

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
- The apps follow the browser's `prefers-color-scheme` with no switch of their own (css custom
  properties in `src/styles.scss`).
- Dates and times in the browser's locale via the `localDateTime` pipe
  (`shared/local-date-time.pipe.ts`), never angular's `DatePipe` or a fixed format.

## Privacy

- Nothing is loaded from outside `<root_domain>` (no cdn, font service, analytics or other third
  party); everything is bundled (e.g. `@fontsource`, `swagger-ui-dist`) or served by the backend.
  The content security policy (`contentSecurityPolicy`) allows only the page's own origin and
  `api.<root_domain>`, it is never widened beyond `<root_domain>`.
- User data leaves the backend only on the user's explicit request (e.g. text discovery) and only to
  providers that do not collect it (openrouter `provider.data_collection: deny`).
