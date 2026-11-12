# Frontend

- Every text the user reads is in `i18n/texts.yml` at the root of the repository, english and german: `web` and
  `common` for texts of no app, `<app>.web` and `<app>.common` for the texts of an app. `mise run i18n:generate`
  writes them into `src/app/i18n/catalog.<language>.ts`, which is not edited. A template shows a text with
  `{{ 'feature.key' | t }}`, code with `inject(I18n).t(...)`, a route title with `pageTitle(...)`; placeholders are
  `%{name}`. The compiler refuses an unknown key; sak-i18n refuses a german text that is missing or has other
  placeholders.
- The shapes of the api come from `openapi.json` at the root of the repository, the document the routes of sak-backend
  describe: `mise run api:generate` writes it and `src/app/api/schema.ts` from it (`npm run api:types`,
  openapi-typescript), which is not edited; `api-types.spec.ts` fails while schema.ts is not current. A service uses
  the readable aliases of `src/app/api/models.ts`, never a hand-written copy of a response. A field the schema marks
  optional may be missing or null (the backend leaves out a nullable field with a default when it is null): test it
  with `== null`. Where the backend sends one of a fixed set of values as a string, `models.ts` narrows the field and
  says so.
- The language is the first one of the browser there are texts for, else english; the browser sends the same
  languages to the api, which answers its own texts (errors, labels of the settings, the names of the categories of a
  new grocery list) in it. Data from the backend is never translated in the frontend.
