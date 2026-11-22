# Agent guidelines

Read this file first. It contains rules for the complete repository.

## Prime Directives

- Write all new or changed documentation in ASD-STE100 Simplified Technical English, with the vocabulary of
  [`docs/development/GLOSSARY.md`](docs/development/GLOSSARY.md).
- Every document below `docs/` starts with front matter (`title`, `description`, `weight`, a short `linkTitle` for a
  long title). A link between documents that does not resolve fails `mise run docs:build`.
- Never add, change, move or delete a rule on your own, also when a change, a review or a fix makes it necessary or
  incorrect. A rule is each entry with an id (e.g. `SECURITY-3`) in `docs/development/architecture/` and in
  [UI-UX.md](docs/development/UI-UX.md), and each bullet of this file. Show the user the exact new text (and the old
  text of a changed rule), wait for the explicit agreement, write exactly the agreed text. An agreement is for one
  change; an instruction to move or to shorten a rule is not an agreement to its new text. When code and a rule do
  not agree, stop and ask.
- Write each review to a file of its own in `docs/development/reviews/`. Each finding has an id that is unique among
  all reviews: the prefix of its review and a number. Delete a fixed finding, and a review without findings; its
  prefix may then be used again.
- The nearest `AGENTS.md` adds guidance for its path; the rules of this file still apply.
- Document public APIs and what is not obvious from the code (invariants, lifecycle). Keep comments terse.
- Keep the tests and the internal documentation current. Change public documentation only when its readers must
  learn or do something differently; for an API change, document what a client must change.
- Run verification that can find regressions in the changed area.
- Never log PII: no names, e-mail addresses, login or provider identifiers, tokens, passwords, codes, reset links,
  IP addresses of clients, or full query strings. The IP addresses of the user's own servers are no PII.
- Encrypt all data at rest. Data that is not encrypted is a defect: record it as a feature or an accepted risk until
  it is fixed.
- Protect the privacy of the user: identify each external system a feature contacts and the data the connection
  reveals, the IP address included. A connection that sends data to a third party is off by default and needs an
  activation and the consent of the user, with the name of the receiver (architecture/SYSTEM.md, "Privacy").

## UI/UX

How the apps (frontend, sakadmin ui, sakadmin cli, Android Apps) look and behave for their users is in
[docs/development/UI-UX.md](docs/development/UI-UX.md), follow it for every change of a ui.

## Architecture

How SAK is built is in three levels below `docs/development/architecture/`, each adding to the ones above and never
repeating them:

1. [SYSTEM.md](docs/development/architecture/SYSTEM.md): the whole system (its parts, the backend layers, the api
   contract, the model, the external systems, the server of a deployment, sakadmin, the cloud resources and their
   labels, privacy, security, the tests).
2. [components/INDEX.md](docs/development/architecture/components/INDEX.md): what all components share (the shared parts, the
   list-based apps, the frontend).
3. `components/<COMPONENT>.md`: each component, e.g. [NOTES.md](docs/development/architecture/components/NOTES.md); the
   `AGENTS.md` of a component's directory links its document.

Every change keeps to its level and to the levels above; a change that has to break a rule changes its document in
the same commit and says why. A rule two components need moves up to components/INDEX.md. A new rule needs the explicit consent
of the user (see "Prime Directives"). Every rule has an id, e.g. `MODEL-1`
(refer to SYSTEM.md); refer to a rule by its id.

## Services (`sak-backend`)

- `<entity>Service` in `io.pelle.sak.backend.<app>`, next to the app's routes; the layers are `LAYER-1`
  (architecture/SYSTEM.md, "Backend layers").

## API (`/api`)

- The contract (errors, pagination, request and response classes) is in architecture/SYSTEM.md, "API". In the code:
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
    - Lists are paginated with the shared `app-pagination` (`shared/pagination.ts` of `core`), `PAGE_SIZE`
      items a page, the page in the `offset` query parameter; only choices and suggestions (a
      select, a datalist) load a whole list, page by page with `fetchAll` (`shared/api.ts` of `core`).
    - Create on `/<entities>/create`, edit on `/<entities>/<id>/edit`; the tabs of a detail view are
      child routes, a `nav nav-tabs` right above the `<router-outlet />`, and each tab starts with its
      card, so the card is the folder of the active tab; an error of the tab is an alert inside that card.
    - Layout, spacing, forms, buttons, tables, cards, alerts and tabs are bootstrap's classes in the
      templates, its spacing and corner radius included; a component sets its own layout with
      `host: { class: '…' }`. Bootstrap and its look come from `sak-theme` (its css listed in
      `angular.json`): `bootstrap.css` there maps bootstrap onto the tokens, the components read the
      `--sak-*` tokens directly, `src/styles.scss` keeps the post-it colors. No color literal elsewhere. Custom css only for what
      bootstrap has no class for (the post-its, the terminal look of `app-code-block`, the charts,
      the stop button of the recording overlay), in the component's own stylesheet.

## Naming

- Every cloud resource of a deployment is named `sak-<name>`, with a suffix for what it is where a deployment has
  more than one of a kind: the server, the firewall and the ssh key `sak-<name>`, the data volume `sak-<name>-data`,
  the primary ips `sak-<name>-ipv4` and `sak-<name>-ipv6`. A name is at most 54 characters, so the longest cloud
  name stays a dns label of 63. DNS records are named by the dns mode, not by this rule.
- A class (a struct in rust) that manages resources outside the process, at a cloud provider,
  a container runtime, a remote api, is called `<name>Manager` after what it manages, e.g.
  `DeploymentManager` (`sak-admin-cli`, the sak deployments at the providers). A manager is the one
  place its resources are looked up, created, changed and removed from; the rest of the code
  asks it and holds no client of its own.

## Cloud resources (`sak-admin-cli/cloud`)

- The labels, the identity and what must stay are in architecture/SYSTEM.md, "Cloud resources". In the code:
- Whatever creates resources sets the name label and the password hash label on creation, and declares them as the
  identity (`with_identity`, checked by `api::identity`); a resource of SAK without them is a defect.
- A registry that may destroy declares its owner labels (`Registry::owned_by`), and only the resources that may be
  replaced are `Registry::recreatable`.
- The password hash is `keys::password_hash` in `sak-admin-cli` (30 bytes of the master key as hex); the firewall selects its
  servers by the name label alone.

## Texts (`i18n/texts.yml`)

- Every text a user reads goes into `i18n/texts.yml`, never into code or a generated file (`I18N-1` in SYSTEM.md);
  run `mise run i18n:generate` after a change of it.
- `admin_cli.cli` texts only the sakadmin cli shows, `admin_cli.ui` texts only the sakadmin ui shows, `admin_cli.common` texts both show
  (the messages of core, the resource kinds and statuses, the actions of a plan), read as `cli.*`, `ui.*` and
  `common.*`. A text moves to `admin_cli.common` once both use it. `sak-admin-cli/src-tauri/locales/` is generated from it.
- `sak_backup` has the same parts for sakbackup (`sak-backup/src-tauri/locales/`). `desktop_shared` has the texts of
  `sak-desktop-shared` (e.g. the sign-in), which each desktop app shows next to its own; a key is in one of them only.

## Privacy in the code

- The boundaries are in architecture/SYSTEM.md, "Privacy". In the code: fonts and libraries are bundled (e.g.
  `@fontsource`, `swagger-ui-dist`), the content security policy is `contentSecurityPolicy` and is never widened
  beyond `<root_domain>`, and calls to openrouter send `provider.data_collection: deny`.
- A type that holds a secret never shows it as text: its `toString()` (or what the language has for it) leaves the
  value out, so a secret can not get into a log or an error message by accident.

## Accepted risks

- The known weaknesses kept on purpose are in
  [docs/development/ACCEPTED-RISKS.md](docs/development/ACCEPTED-RISKS.md). A change that touches one keeps the
  trade-off or revisits it there.

## Additional Agent Rules & Context

- [README.md](README.md) — general project overview.
- [docs/development/architecture/SYSTEM.md](docs/development/architecture/SYSTEM.md) — how SAK is built, the first
  of the three levels of the architecture.
- `docs/development/reviews/` — the reviews, each finding with its unique id.