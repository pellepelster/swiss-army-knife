# Agent guidelines

Read this file first. It contains rules for the complete repository.

## Prime Directives

- Use ASD-STE100 Simplified Technical English for all new or changed documentation (repository and public
  documentation!) Find the canonical vocabulary in [`docs/development/GLOSSARY.md`](docs/development/GLOSSARY.md).
- Every document below `docs/` starts with front matter (`title`, `description`, `weight`, a short
  `linkTitle` where the title is long), so the site of the documentation (`site/`, `mise run docs:serve`) lists it.
  A link between documents that does not resolve fails `mise run docs:build`.
- Never create a Feature Decision Record (`docs/development/fdr/`) on your own; write one only when the user asks for
  it. Keeping an existing FDR current when its feature changes is still required.
- The nearest applicable `AGENTS.md` controls path-specific guidance. Root rules still apply when nested guidance is
  more specific.
- Add code documentation for public APIs and important fields, functions, types, invariants, and lifecycle behavior.
  Future maintainers must not have to infer this information from call sites.
- Keep relevant tests and internal documentation current. Change public documentation only when readers must learn or do
  something differently. For API changes, document the required client migration; keep implementation details and
  unchanged behavior out. Apply the release-note scope below.
- Run verification that can find regressions in the changed area.
- Never log PII: no raw login names, display names, email addresses, submitted auth identifiers, OAuth/OIDC provider
  subjects, tokens, passwords, auth codes, reset links, raw IPs of clients or users, or full query strings. The IP
  addresses of the user's own servers (the server and the primary IPs of a deployment) are no PII: sakctl may show
  and log them, e.g. in `list` and while it provisions a server.
- Encrypt all data at rest: databases, files, volumes, backups and logs. Data that is not encrypted at rest is a
  defect; record it as a feature or an accepted risk until it is fixed.
- Protect user privacy when you design or build a feature. Identify each external system that the feature contacts and
  the data that the connection reveals, including the user’s IP address. Avoid unnecessary third-party connections.
  A connection that sends data to a third party is off by default: it needs an explicit activation, and the consent of
  the user before data goes out, with the name of the receiver (architecture/SYSTEM.md, "Privacy").
- Keep comments terse and condensed, only add comments for non-obvious behavior

## UI/UX

How the apps (frontend, CLI, wizard, Android Apps) look and behave for their users is in
[docs/development/UI-UX.md](docs/development/UI-UX.md), follow it for every change of a ui.

## Architecture

How SAK is built is in three levels below `docs/development/architecture/`, each adding to the ones above and never
repeating them:

1. [SYSTEM.md](docs/development/architecture/SYSTEM.md): the whole system (its parts, the server of a deployment,
   sakctl, the cloud resources and their labels, privacy, security, the tests).
2. [apps/INDEX.md](docs/development/architecture/apps/INDEX.md): what all apps share (the layers, the persistence, the settings,
   the api contract, the shared parts, the frontend, privacy in the apps).
3. `apps/<APP>.md`: each app, e.g. [NOTES.md](docs/development/architecture/apps/NOTES.md); the `AGENTS.md` of an
   app's directory links its document.

Every change keeps to its level and to the levels above; a change that has to break a rule changes its document in
the same commit and says why. A rule two apps need moves up to apps/INDEX.md.

## Persistence (`sak-model`)

- Every table, link tables included, has `created_at`, `updated_at` and `deleted_at` (`SchemaTest`
  checks it):
    - `created_at` is set when the row is inserted, never null;
    - `updated_at` is null until the row really changes, an insert never sets it;
    - `deleted_at` is null until the row is deleted, a delete sets nothing else.
- Soft delete (architecture/apps/INDEX.md, "Persistence"): every read skips the rows with `deleted_at` set, a value unique among
  the live rows has a partial unique index (`WHERE deleted_at IS NULL`).
- The repository sets the times, from the `Clock` it is constructed with (the service hands its own on);
  no repository method takes a time or an entity built outside, inserts and updates take the
  fields and answer the row as it is stored.
- `SettingService` hands the values out as they are; the consumer of a setting that is a secret keeps it out of logs,
  e.g. by wrapping it in `Secret`, whose `toString()` never shows the value. Configuration and value classes holding a
  secret override `toString()` the same way.
- A setting an admin changes is a `SettingDefinition<T>` of a `SettingScope`, read and written through the
  `SettingsService` of its scope (`get(OpenRouterManager.ENABLED)` is a `Boolean?`, null while its scope is
  turned off); a new scope is a `StoredSettingsService`
  (or a class extending it, e.g. `OpenRouterManager`) added to the `SettingsRegistry` in `SakBackend.kt`. The api and the settings page follow without changes.
- `sak-model` holds the schema, the entities and their repositories, nothing else. Database access
  only in `<entity>Repository`, the only class touching jOOQ and its generated tables;
  `SakDatabase.dsl` is `internal`, the compiler keeps it in the module.

## Services (`sak-backend`)

- `<entity>Service` in `io.pelle.sak.backend.<app>`, next to the app's routes; the layers are in architecture/apps/INDEX.md,
  "Backend layers".

## API (`/api`)

- The contract (errors, pagination, request and response classes) is in architecture/apps/INDEX.md, "API". In the code:
- Routes install `ApiErrors` (`sak-api`, `io.pelle.sak.api`) next to content negotiation; errors are answered with
  `call.respondErrors`; the largest `limit` is `MAX_LIMIT` (`sak-api`).
- A service answers a page as `Page<T>` (`sak-api`, its items and the total); the route answers
  `page.of(it.map { it.response() })` with the `PageRequest` of the call. A list that is there as a whole is paged
  with `page.of(all)`.
- A point in time in a response is a `String` written with `Instant.apiTime()` (`sak-api`), never `toString()`;
  something that never ends has `null`, not a far away time.
- Bodies received are `<name>Request`, bodies answered `<name>Response`, also every class nested
  in them (e.g. `PageResponse`, `SiteMetricsResponse`); the one exception is `ApiErrorMessage`, an error of an
  `ApiErrorResponse`.
- Routes of an app in `io.pelle.sak.backend.<app>`, its request/response classes in
  `io.pelle.sak.backend.<app>.model`, one class per file named like the class. The other modules
  answering on `/api` keep theirs the same way in `io.pelle.sak.<module>.model` (`sak-api`,
  `sak-auth`, `sak-logs`).

## Frontend code (`sak-frontend`)

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

- The look follows [docs/development/UI-UX.md](docs/development/UI-UX.md); the code rules behind it:
    - Lists are paginated with the shared `app-pagination` (`shared/pagination.ts`), `PAGE_SIZE`
      items a page, the page in the `offset` query parameter; only choices and suggestions (a
      select, a datalist) load a whole list, page by page with `fetchAll` (`shared/api.ts`).
    - Create on `/<entities>/create`, edit on `/<entities>/<id>/edit`; the tabs of a detail view are
      child routes.
    - Layout, spacing, forms, buttons, tables, cards, alerts and tabs are bootstrap's classes in the
      templates, its spacing and corner radius included; a component sets its own layout with
      `host: { class: '…' }`. Bootstrap and its look come from `sak-theme` (its css listed in
      `angular.json`): `bootstrap.css` there maps bootstrap onto the tokens, `src/styles.scss` only
      aliases the names the components read (`--wall`, `--ink`, `--line`, `--sheet`, `--muted`,
      `--danger`) and keeps the post-it colors. No color literal elsewhere. Custom css only for what
      bootstrap has no class for (the post-its, the terminal look of `app-code-block`, the charts,
      the stop button of the recording overlay), in the component's own stylesheet.

## Naming

- Every cloud resource of a deployment is named `sak-<name>`, with a suffix for what it is where a deployment has
  more than one of a kind: the server, the firewall and the ssh key `sak-<name>`, the data volume `sak-<name>-data`,
  the primary ips `sak-<name>-ipv4` and `sak-<name>-ipv6`. A name is at most 54 characters, so the longest cloud
  name stays a dns label of 63. DNS records are named by the dns mode, not by this rule.
- A class (a struct in rust) that manages resources outside the process, at a cloud provider,
  a container runtime, a remote api, is called `<name>Manager` after what it manages, e.g.
  `DeploymentManager` (`sak-cli`, the sak deployments at the providers). A manager is the one
  place its resources are looked up, created, changed and removed from; the rest of the code
  asks it and holds no client of its own.

## Cloud resources (`sak-cli/cloud`)

- The labels, the identity and what must stay are in architecture/SYSTEM.md, "Cloud resources". In the code:
- Whatever creates resources sets the name label and the password hash label on creation, and declares them as the
  identity (`with_identity`, checked by `api::identity`); a resource of SAK without them is a defect.
- A registry that may destroy declares its owner labels (`Registry::owned_by`), and only the resources that may be
  created anew are `Registry::recreatable`.
- The password hash is `keys::password_hash` in `sak-cli` (30 bytes of the master key as hex); the firewall selects its
  servers by the name label alone.

## Texts of sakctl (`sak-cli/src-tauri/locales/`)

- `cli:` texts only the command line shows, `ui:` texts only the window shows, `shared:` texts both show (the
  messages of core, the resource kinds and statuses, the actions of a plan). A text moves to `shared:` once both use
  it. Every key is in `en.yml` and `de.yml`.

## Privacy in the code

- The boundaries are in architecture/SYSTEM.md, "Privacy". In the code: fonts and libraries are bundled (e.g.
  `@fontsource`, `swagger-ui-dist`), the content security policy is `contentSecurityPolicy` and is never widened
  beyond `<root_domain>`, and calls to openrouter send `provider.data_collection: deny`.

## Accepted risks

- The known weaknesses kept on purpose are in
  [docs/development/ACCEPTED-RISKS.md](docs/development/ACCEPTED-RISKS.md). A change that touches one keeps the
  trade-off or revisits it there.

## Additional Agent Rules & Context

- [README.md](README.md) — general project overview.
- [docs/development/architecture/SYSTEM.md](docs/development/architecture/SYSTEM.md) — how SAK is built, the first
  of the three levels of the architecture.
- `docs/development/fdr/INDEX.md` — Swiss Army Knife feature behavior and rationale.