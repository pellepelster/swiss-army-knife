# Frontend

- Every text the user reads comes from the texts of its feature, `src/app/<feature>/texts.ts`, english and german
  (`texts(en, de)`), registered in `src/app/i18n/catalog.ts`. A template shows it with `{{ 'feature.key' | t }}`,
  code with `inject(I18n).t(...)`, a route title with `pageTitle(...)`; placeholders are `%{name}`. The compiler
  refuses an unknown key and a german text that is missing, `i18n/texts.spec.ts` placeholders that differ.
- The language is the first one of the browser there are texts for, else english; the browser sends the same
  languages to the api, which answers its own texts (errors, labels of the settings, categories) in it. Data from the
  backend is never translated in the frontend.
