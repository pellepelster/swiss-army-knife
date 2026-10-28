# Agent guidelines

Read this file first. It contains rules for the complete repository.

## Prime Directives

- Use ASD-STE100 Simplified Technical English for all new or changed documentation (repository and public
  documentation!) Find the canonical vocabulary in [`GLOSSARY.md`](GLOSSARY.md).
- The nearest applicable `AGENTS.md` controls path-specific guidance. Root rules still apply when nested guidance is
  more specific.
- Add code documentation for public APIs and important fields, functions, types, invariants, and lifecycle behavior.
  Future maintainers must not have to infer this information from call sites.
- Keep relevant tests and internal documentation current. Change public documentation only when readers must learn or do
  something differently. For API changes, document the required client migration; keep implementation details and
  unchanged behavior out. Apply the release-note scope below.
- Run verification that can find regressions in the changed area.
- Never claim full verification when only a partial signal was run.
- Never log PII: no raw login names, display names, email addresses, submitted auth identifiers, OAuth/OIDC provider
  subjects, tokens, passwords, auth codes, reset links, raw IPs, or full query strings.
- Encrypt all data at rest: databases, files, volumes, backups and logs. Data that is not encrypted at rest is a
  defect; record it as a feature or an accepted risk until it is fixed.
- Protect user privacy when you design or build a feature. Identify each external system that the feature contacts and
  the data that the connection reveals, including the user’s IP address. Avoid unnecessary third-party connections, and
  make necessary connections clear to the user.

## Product Boundaries And Instruction Routing

protocols. Unless a path is explicitly Authling-owned or shared, existing
repository content belongs to Chatto.

- **Authling** is the independent identity-provider product under `authling/`.
  It is not a Chatto component, runtime unit, feature, or deployment mode.
- **Runling** is the independent TypeScript workflow and agent orchestrator
  under `packages/runling/`. It includes a library, CLI, and web console. It
  uses the root pnpm workspace and has its own version, CI job, and npm release.
  Keep its public APIs and runtime independent of Chatto and Authling.
- **Shared framework code** is application-neutral event-sourcing, JetStream
  client, embedded NATS, data-cryptography, and configuration-loading
  machinery intended for consumption by both products. The independently
  versioned but unstable modules live under `pkg/events/`,
  `pkg/jetstreamutil/`, `pkg/natsruntime/`, `pkg/datacrypto/`, and
  `pkg/appconfig/`.

Authling is in this repository temporarily. It provides the second application
needed to extract and validate the shared framework. Move Authling to its own
repository when the shared boundary is stable. Do not describe this repository
as its permanent home. Do not add coupling that makes this move more difficult.

## UI/UX

How the apps (frontend, CLI, wizard, Android Apps) look and behave for their users is in [UI-UX.md](UI-UX.md), follow it
for every change of a ui.

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
  (versioned, the newest encrypts, older ones are encrypted anew on start; the provisioning of the server creates them),
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
  with the `offset` and `limit` query parameters; `limit` is 50 by default and at most 100 (`MAX_LIMIT`, `sak-api`).
- Bodies received are `<name>Request`, bodies answered `<name>Response`, also every class nested
  in them (e.g. `PageResponse`, `SiteMetricsResponse`); a domain class is mapped to its response
  class, it never crosses the api itself.
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

## Wizard code (`sak-cli`)

- The rust of `src-tauri` is three modules: `core` holds what both modes share (the checks on
  startup, the `DeploymentManager`, the deployments and their plans, the provisioning, the keys,
  the providers and their tokens, the texts) and knows neither tauri nor clap (a test checks it);
  `gui` is tauri, the window, the commands of the pages as thin calls into core and the checks on
  its splash screen; `cli` is clap and the log.
- Creating or deploying ends with the provisioning of the server: `core/provision.rs` runs
  `ansible-playbook pelle_io.sak.server` in the image of the tools (docker or podman) against it,
  the inventory, the ssh configuration, the client key and the known host key written to a
  private directory for the run and mounted read-only; every line ansible prints goes to the
  page's terminal or stdout. The server's host key is derived from the password like the client
  key (`keys::derive_host_key`) and installed by cloud-init, so the first connection is verified
  (`StrictHostKeyChecking yes`) and nothing has to be kept; the user data is part of the server's
  declaration, a changed one is a recreate.

## Naming

- A class (a struct in rust) that manages resources outside the process, at a cloud provider,
  a container runtime, a remote api, is called `<name>Manager` after what it manages, e.g.
  `DeploymentManager` (`sak-cli`, the sak deployments at the providers). A manager is the one
  place its resources are looked up, created, changed and removed from; the rest of the code
  asks it and holds no client of its own.

## Resource labeling (cloud resources)

- Every cloud resource (server, volume, primary ip, firewall, ssh key, dns zone and record, …)
  carries the label `sak.pelle.io/name=<sak name>`, `<sak name>` being the unique name of the
  swiss army knife deployment the resource belongs to (see [GLOSSARY.md](GLOSSARY.md)). Whatever creates
  resources (`sak-cli/cloud`) sets it on creation; the label tells the resources
  of one deployment from those of another and from everything else in the cloud project.
- SAK removes only resources that carry the label `sak.pelle.io/name=<sak name>` of the deployment. A resource
  without this label, or with the label of another deployment, is never removed, also when its name agrees.
- Labels sak keeps for itself live under the `sak.pelle.io/` prefix, nowhere else; a label a
  user declares never does. Every resource of a deployment also carries
  `sak.pelle.io/password-hash=<hash>`, the argon2id hash of the deployment's password (`keys::password_hash` in
  `sak-cli`, 30 bytes as hex), so the wizard can check a password
  against a deployment before it touches it: a deploy or destroy stops when the hash on any of
  its resources differs (the firewall selects its servers by the name label alone); the
  password itself is nowhere.
- Every resource of a deployment also carries `sak.pelle.io/dns-mode=<mode>`, the dns mode of the deployment
  (`domain` or `subdomain`, see [GLOSSARY.md](GLOSSARY.md)), and `sak.pelle.io/root-domain=<root domain>`. The
  wizard reads them back for a deploy; a change of either is a label change, never a recreate. The dns mode on a
  zone or record tells the wizard which deployments use a root domain (`RootDomain`, `sak-cli/cloud`).

## Privacy

- Nothing is loaded from outside `<root_domain>` (no cdn, font service, analytics or other third
  party); everything is bundled (e.g. `@fontsource`, `swagger-ui-dist`) or served by the backend.
  The content security policy (`contentSecurityPolicy`) allows only the page's own origin and
  `api.<root_domain>`, it is never widened beyond `<root_domain>`.
- User data leaves the backend only on the user's explicit request (e.g. text discovery) and only to
  providers that do not collect it (openrouter `provider.data_collection: deny`).

## Accepted risks

Known weaknesses that are kept on purpose; a change that touches them keeps the trade-off or
revisits it here.

- The login link budget can be used up ([#33](https://github.com/pellepelster/swiss-army-knife/issues/33)). `login`
  (`sak-auth`,
  `AuthRoutes.kt`) sends at most 60 links an hour altogether (`linksPerHour`), and at most 5 an hour
  to an address from one client (`loginsPerAddress`, keyed by address and client ip). Someone who
  knows an allowed address and has 12 client ips can use up the hour's links, and no allowed
  address gets a link until the hour is over. Accepted: the global ceiling is what keeps the
  server from being used to send mail, an outage of the login for an hour is the price, and the
  apps have few allowed addresses whose sessions last 30 days.

## Additional Agent Rules & Context

- [README.md](README.md) — general project overview.
- `docs/fdr/INDEX.md` — SwissArmyKnife feature behavior and rationale.