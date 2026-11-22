---
title: "Review: the documents"
linkTitle: "Documents"
description: "The review of all Markdown files of the repository: whether their links resolve and whether they agree with each other."
weight: 13
---

# Review: the documents

**Created:** 2026-11-22

Scope: the 67 Markdown files of the repository (`AGENT.md`, the 19 `AGENTS.md`, the `README.md` files, `docs/`,
`site/content/`), in the working tree after the rules of `AGENT.md` moved to
[SYSTEM.md](../architecture/SYSTEM.md) and [the architecture of the components](../architecture/components/INDEX.md).
The question: whether every link and every id leads somewhere, and where two documents say different things about
the same thing. The content of a rule, the code and the texts for users were not reviewed.

How sure a finding is: the links, the anchors, the ids, the front matter and the lengths of the lines were
**checked** with a script over all files. The other findings were **read** in the files they name.

**Checked again:** 2026-11-22, after the fixes of `DOC-1`, `DOC-3`, `DOC-4`, `DOC-5`, `DOC-7` and `DOC-10`, which are
in the history of git. `DOC-9` was wrong and is withdrawn: an `INDEX.md` is the page of its directory, its weight
orders the directory and not the documents in it. The three findings left change bullets of `AGENT.md`, which need
an agreement to their text.

What is good: every relative link and every anchor resolves, in all 67 files. No rule id is defined twice, and every
id a document refers to is a rule, an open finding or a finding its review names as fixed. Every document below
`docs/` has its front matter. Every `AGENTS.md` links a document that is there. The documents for users say "SAK
instance" and not "deployment", as [GLOSSARY.md](../GLOSSARY.md) asks.

## Should fix

### `DOC-2` The privacy bullet of AGENT.md and `PRIVACY-1` name two domains (read)

`AGENT.md`, "Privacy in the code": the content security policy "is never widened beyond `<root_domain>`".
`PRIVACY-1` says it "allows only the page's own origin and `api.<base domain>`", and no page loads anything "from
outside the root domain". [GLOSSARY.md](../GLOSSARY.md) has the root domain and the base domain as two things: in
the dns mode `subdomain` the base domain is `<name>.<root domain>`. So the bullet allows more than the rule. The
bullet also says `PRIVACY-1` and `PRIVACY-4` again (bundled fonts, no collection at the AI provider).

Fix: decide which domain is the limit; then merge the bullet into `PRIVACY-1` and `PRIVACY-4`, as the other bullets
of the code were merged.

## Could fix

### `DOC-6` AGENT.md does not name the issues (checked)

[ISSUES.md](../ISSUES.md) is linked from [ACCEPTED-RISKS.md](../ACCEPTED-RISKS.md) and from SYSTEM.md. `AGENT.md`
names the reviews and the accepted risks, not the issues, so nothing tells an agent that an open issue is recorded
there and not on GitHub.

Fix: a line in `AGENT.md` for the issues.

### `DOC-8` The last section of AGENT.md repeats the sections before it (read)

"Additional Agent Rules & Context" lists SYSTEM.md and `docs/development/reviews/`. "Architecture" and the prime
directives name both already. Only the line for `README.md` is new.

Fix: the section keeps what the others do not have, or goes.

## Notes

- The scope of two reviews names the rules as they were: `MODEL-1` to `MODEL-8` in
  [the review of the data model](data-model-review.md), `API-1` to `API-6` and `MODEL-1` to `MODEL-8` in
  [the review of the architecture of the backend](backend-architecture-review.md). That is what they checked;
  `MODEL-9`, `API-7` and the texts added to the other rules on 2026-11-22 were not reviewed against the code.
- [ISSUES.md](../ISSUES.md) names things that are gone (`sakctl`, `doc/REVIEW.md`, `ARCHITECTURE.md`, `FDR-007`,
  `SAKCTL-8`). Its introduction says so.

## Not checked

- Links to other sites.
- Whether the code keeps a rule, and whether a screenshot shows what its page says.
- `.claude/skills/`, which is no document of the repository.
