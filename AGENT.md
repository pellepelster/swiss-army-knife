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
- Layout, spacing, forms, buttons, tables, cards, alerts and tabs are bootstrap's classes in the
  templates, its default spacing and corner radius included; a component sets its own layout with
  `host: { class: '…' }`. Bootstrap and its look come from `sak-theme` (`"sak-theme":
  "file:../sak-theme"`, its css listed in `angular.json`): `bootstrap.css` there maps bootstrap onto
  the tokens, `src/styles.scss` only aliases the names the components read (`--wall`, `--ink`,
  `--line`, `--sheet`, `--muted`, `--danger`) to them and keeps the post-it colors. No color literal
  elsewhere. Custom css only for what bootstrap has no class for (the post-its, the terminal look
  of `app-code-block`, the charts, the recording overlay's stop button), in the component's own
  stylesheet.
- Copying to the clipboard is `app-copy-button` (`shared/copy-button.ts`), never written anew.
- The apps follow the browser's `prefers-color-scheme` with no switch of their own (the tokens of
  `sak-theme` flip with it).
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

## UI (both apps)

- A button or link that creates or adds something starts with the plus sign of `sak-theme`,
  `<i class="sak-icon-plus" aria-hidden="true"></i>` before its text (or alone, with an
  `aria-label`): add a provider, new note, create redirect, the submit of a create form. A button
  creating something of a kind shows the kind after the plus (`+` then the camera for a note with
  a photo). The same sign everywhere, in the wizard and the web ui; tests check it in both
  (`create-buttons.spec.ts`, the test in `sak-wizard/src-tauri/src/main.rs`).

## UI (`sak-wizard`)

- One view at a time, a page with a header and, when a page has them, actions at the bottom.
  Buttons moving through the wizard (next, back, cancel, plan, the final create) sit in the button
  group at the bottom right (`.page-footer`), buttons acting on the data of the view (add a
  provider, create a deployment) in the one at the top right of the page header.
- A deployment in the list opens its page (its state, location and resources); what acts on it
  (redeploy) sits at the top right there. A redeploy runs the plan and the apply of a create for
  a deployment that exists, after its password was checked against the hash on its server, and
  may create its server anew; the data volume and the addresses stay.
- The rust of `src-tauri` is three modules: `core` holds what both modes share (the
  `DeploymentManager`, the deployments and their plans, the keys, the providers and their tokens,
  the texts) and knows neither tauri nor clap (a test checks it); `gui` is tauri, the window, the
  commands of the pages as thin calls into core, the checks of the splash screen and the container
  runtime; `cli` is clap and printing. `sak-wizard list`, `create [<sak name>]` and
  `redeploy <sak name>` run on the command line: the token from `HCLOUD_TOKEN`, the name, password
  and location from the arguments or `SAK_NAME`, `SAK_PASSWORD` and `SAK_LOCATION`, `--help` says
  it all; without a command the window opens. `mise run wizard:cli -- <command>` runs it with the
  token from pass.
- The look is bootstrap on `sak-theme` (`bootstrap.css` maps it onto `tokens.css`; the fonts,
  `components.css`), copied into `ui/theme/` by `mise run wizard:theme`; `ui/style.css` holds the
  layout and names no color of its own, every color is a `var(--sak-…)` (a test checks it). The
  toolbars are `btn-group`s, `btn-primary` the one action of a page, `btn-outline-secondary` every
  other button; states are the theme's leds (`.sak-led`, `.sak-status`), not glyphs.

## Naming

- A class (a struct in rust) that manages resources outside the process, at a cloud provider,
  a container runtime, a remote api, is called `<name>Manager` after what it manages, e.g.
  `DeploymentManager` (`sak-wizard`, the sak deployments at the providers). A manager is the one
  place its resources are looked up, created, changed and removed from; the rest of the code
  asks it and holds no client of its own.

## Resource labeling (cloud resources)

- Every cloud resource (server, volume, primary ip, firewall, ssh key, dns zone and record, …)
  carries the label `sak.pelle.io/name=<sak name>`, `<sak name>` being the unique name of the
  swiss army knife deployment the resource belongs to (see the glossary). Whatever creates
  resources (`sak-wizard/cloud`, `terraform`) sets it on creation; the label tells the resources
  of one deployment from those of another and from everything else in the cloud project.
- Labels sak keeps for itself live under the `sak.pelle.io/` prefix, nowhere else; a label a
  user declares never does. The server of a deployment also carries
  `sak.pelle.io/password-hash=<hash>`, the argon2id hash of the deployment's password
  (`keys::password_hash` in `sak-wizard`, 30 bytes as hex), so the wizard can check a password
  against a deployment before it touches it; the password itself is nowhere.

## Privacy

- Nothing is loaded from outside `<root_domain>` (no cdn, font service, analytics or other third
  party); everything is bundled (e.g. `@fontsource`, `swagger-ui-dist`) or served by the backend.
  The content security policy (`contentSecurityPolicy`) allows only the page's own origin and
  `api.<root_domain>`, it is never widened beyond `<root_domain>`.
- User data leaves the backend only on the user's explicit request (e.g. text discovery) and only to
  providers that do not collect it (openrouter `provider.data_collection: deny`).

## Glossary

- **sak name**: the unique name of a swiss army knife deployment, one per deployment and never
  shared by two; every cloud resource of the deployment carries it in the label
  `sak.pelle.io/name=<sak name>` (see "Resource labeling").
- **root domain** (`root_domain`): the domain a deployment serves its apps under, `pelle.io` for
  the production deployment, `localhost` locally.

## Accepted risks

Known weaknesses that are kept on purpose; a change that touches them keeps the trade-off or
revisits it here.

- The login link budget can be used up
  ([#33](https://github.com/pellepelster/swiss-army-knife/issues/33)). `login` (`sak-auth`,
  `AuthRoutes.kt`) sends at most 60 links an hour altogether (`linksPerHour`), and at most 5 an hour
  to an address from one client (`loginsPerAddress`, keyed by address and client ip). Someone who
  knows an allowed address and has 12 client ips can use up the hour's links, and no allowed
  address gets a link until the hour is over. Accepted: the global ceiling is what keeps the
  server from being used to send mail, an outage of the login for an hour is the price, and the
  apps have few allowed addresses whose sessions last 30 days.
