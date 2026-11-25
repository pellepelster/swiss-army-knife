# Frontend

The rules of the code of the web app are `WEB-5` to `WEB-10` in [components/INDEX.md](../docs/development/architecture/components/INDEX.md#web-app), the texts are `I18N-1` in [SYSTEM.md](../docs/development/architecture/SYSTEM.md#texts). What follows adds to them.

- One Angular application, the shell (`src/app`), and the libraries of `projects/`, compiled from their sources
  with it (`paths` of `tsconfig.json`, no build of their own):
  - `core` (`@sak/core`): what every app needs, the api (`api/`), the session (`auth/`), the texts (`i18n/`) and the
    shared parts of a page (`shared/`).
  - `testing` (`@sak/testing`): what the specs of every app share.
  - one for each app and maintenance page (`@sak/<app>`): `grocery`, `todo`, `notes`, `hosting`, `redirects`,
    `backups`, `users`, `status`, `settings`. Its `AGENTS.md` links its architecture document, or the architecture
    of the components where it has none of its own (`users`, `settings`).
  - The shell keeps what is of no app: the home, the login and the sign-in, the download, the pages that connect
    an app, the list of the apps (`apps.ts`) and the routes in their order (`app.routes.ts`).
- Who imports whom, `src/app/boundaries.spec.ts` fails on anything else: an app imports `@sak/core` (and
  `@sak/testing` in its specs), `redirects` also `@sak/hosting` (the buckets a redirect points to); `core` imports
  no app; only the shell imports the apps. Inside a library the imports are relative, across libraries by name,
  never by a path into another library.
- `src/index.ts` of a library is all the others see of it; an app exports its routes there, their pages are loaded
  with the first visit (`loadComponent`, `loadChildren` inside the library). A page of `core` that is loaded with a
  route has an entry of its own (`@sak/core/app-download`), as have the english texts for the specs
  (`@sak/core/catalog.en`); a dynamic `import('@sak/core')` would load all of it.
- Every library is free of side effects (`sideEffects` of its `package.json`): no code runs when a file is imported,
  so the start bundle holds only what the start uses.
- Every text the user reads is in `i18n/texts.yml` at the root of the repository, english and german: `web` and
  `common` for texts of no app, `<app>.web` and `<app>.common` for the texts of an app. `mise run i18n:generate`
  writes them into `projects/core/src/i18n/catalog.<language>.ts`, which is not edited. A template shows a text with
  `{{ 'feature.key' | t }}`, code with `inject(I18n).t(...)`, a route title with `pageTitle(...)`; placeholders are
  `%{name}`. The compiler refuses an unknown key; sak-i18n refuses a german text that is missing or has other
  placeholders.
- The shapes of the api come from `openapi.json` at the root of the repository, the document the routes of sak-backend
  describe: `mise run api:generate` writes it and `projects/core/src/api/schema.ts` from it (`npm run api:types`,
  openapi-typescript), which is not edited; `api-types.spec.ts` fails while schema.ts is not current. A service uses
  the readable aliases of `projects/core/src/api/models.ts`, never a hand-written copy of a response. A field the schema marks
  optional may be missing or null (the backend leaves out a nullable field with a default when it is null): test it
  with `== null`. Where the backend sends one of a fixed set of values as a string, `models.ts` narrows the field and
  says so.
- The language is the first one of the browser there are texts for, else english; the browser sends the same
  languages to the api, which answers its own texts (errors, labels of the settings, the names of the categories of a
  new grocery list) in it. Data from the backend is never translated in the frontend.
