# Agent guidelines

Read this file first. It contains rules for the complete repository.

## Prime Directives

- Write all new or changed documentation in ASD-STE100 Simplified Technical English, with the vocabulary of
  [`docs/development/GLOSSARY.md`](docs/development/GLOSSARY.md).
- Every document below `docs/` starts with front matter (`title`, `description`, `weight`, a short `linkTitle` for a
  long title). A link between documents that does not resolve fails `mise run docs:build`.
- Never add, change, move or delete content of `docs/development/` and of this file on your own, also when a change,
  a review or a fix makes it necessary or incorrect; the reviews in `docs/development/reviews/` are the exception.
  Show the user the exact new text (and the old text of changed content), wait for the explicit agreement, write
  exactly the agreed text. An agreement is for one change; an instruction to move or to shorten text is not an
  agreement to its new text. When code and a rule do not agree, stop and ask.
- Write and change a rule as [docs/development/RULES.md](docs/development/RULES.md) says.
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

How the apps (frontend, Android apps, desktop apps) look and behave for their users is in
[docs/development/ui-ux/UI-UX.md](docs/development/ui-ux/UI-UX.md) and, for each kind of app, in `UI-UX-WEB.md`, `UI-UX-ANDROID.md`
and `UI-UX-DESKTOP.md` next to it, what only sakadmin does in
[SAKADMIN.md](docs/development/architecture/components/SAKADMIN.md); follow them for every change of a ui.

## Architecture

How SAK is built is in three levels below `docs/development/architecture/`, each adding to the ones above and never
repeating them:

1. [SYSTEM.md](docs/development/architecture/SYSTEM.md): the whole system (its parts, the backend layers, the api
   contract, the model, the external systems, the server of a deployment, the desktop apps, the cloud resources and
   their labels, privacy, security, the tests).
2. [components/INDEX.md](docs/development/architecture/components/INDEX.md): what all components share (the shared
   parts, the list-based apps, the frontend).
3. `components/<COMPONENT>.md`: each component, e.g. [NOTES.md](docs/development/architecture/components/NOTES.md); the
   `AGENTS.md` of a component's directory links its document.

Every change keeps to its level and to the levels above; a change that has to break a rule changes its document in
the same commit and says why. A rule two components need moves up to components/INDEX.md. A new rule needs the
explicit consent of the user (see "Prime Directives"). Every rule has an id, e.g. `MODEL-1` (refer to SYSTEM.md);
refer to a rule by its id.

## Privacy in the code

- A type that holds a secret never shows it as text: its `toString()` (or what the language has for it) leaves the
  value out, so a secret can not get into a log or an error message by accident.

## Accepted risks

- The known weaknesses kept on purpose are in
  [docs/development/ACCEPTED-RISKS.md](docs/development/ACCEPTED-RISKS.md). A change that touches one keeps the
  trade-off or revisits it there.

## Additional Agent Rules & Context

- [README.md](README.md) — general project overview.
- [docs/development/ISSUES.md](docs/development/ISSUES.md) — the open issues. They are kept there and not on GitHub;
  an issue that is done is deleted.
