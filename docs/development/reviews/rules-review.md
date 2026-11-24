---
title: "Review: the rules against RULES.md"
linkTitle: "Rules"
description: "The review of the rules of docs/development against RULE-1 to RULE-10: their form, their titles, their ids, their words and their length."
weight: 15
---

# Review: the rules against RULES.md

**Created:** 2026-11-24

Scope: the 160 rules with an id in `docs/development/` (SYSTEM.md, components/, UI-UX.md; the reviews left out),
against `RULE-1` to `RULE-10` of [RULES.md](../RULES.md), which came after them. Found by a script over every rule and
read where a script can not decide (a title that is only a subject). A new text of a rule needs the agreement of the
user, one rule at a time (AGENT.md); each finding lists the rules, not their new texts.

What is good: no id is used twice; the gaps in the numbering are ids of deleted rules, as `RULE-3` wants; most rules
are one bullet with a bold title, say what is true in the present tense and refer to other rules by their id; no rule
uses "should".

## Should fix

### `RR-1` 39 rules without a title of their own

`RULE-2`: the title says the decision of the rule. These rules have no title that stands on its own, in three kinds:

- The bold words start a sentence that runs on, e.g. `BACKUP-5` "**A backup plan** is what restic backs up …": `UX-5`,
  `WEB-4`, `API-1`, `API-2`, `API-3`, `API-4`, `SERVER-7`, `TEST-1`, `TEST-2`, `TEST-3`, `BACKUP-1`, `BACKUP-5`,
  `BACKUP-8`, `HEALTH-1`, `HOSTING-1`, `HOSTING-3`, `FRONTEND-1`, `FRONTEND-5`, `FRONTEND-6`, `NOTES-2`, `NOTES-3`,
  `REDIRECTS-1`, `REDIRECTS-2`, `REDIRECTS-3`, `REDIRECTS-4`, `REDIRECTS-5`, `SAKADM-5`, `CLI-1`.
- No title at all, e.g. `HOSTING-4` "Create, change the domains of, and delete a bucket; …": `SERVER-1`, `SERVER-3`,
  `SERVER-4`, `SERVER-5`, `SERVER-6`, `HOSTING-4`, `HOSTING-5`, `HOSTING-6`, `NOTES-4`, `NOTES-5`.
- A colon in place of the full stop: `UI-11` "**One line per event:** …".

Fix: a title for each, which says its decision (`RULE-2`), and the text after it.

### `RR-2` Titles that name only a subject

`RULE-2`: the title says the decision, not only its subject. These titles name a subject:

- the component documents: `GROCERY-1` "Lists.", `GROCERY-13` "Categories.", `GROCERY-14` "Bought items.", `GROCERY-15`
  "Quantity.", `NOTES-1` "Lists.", `TODO-1` "Lists.", `TODO-3` "Done.", `TODO-4` "Due date.", `TODO-5` "Priority.",
  `TODO-6` "Purge.", `SHARED-2` "Labels.", `SAKADM-7` "Certificates.";
- SYSTEM.md and UI-UX.md: `CLOUD-4` "Identity.", `CLOUD-8` "What stays.", `WEB-2` "Detail view.";
- the rules of `RR-1` whose bold words are a subject (`API-1` "Errors", `API-2` "Lists", `API-3` "Times", `TEST-1`,
  `TEST-2`, `BACKUP-1`, `HOSTING-1`, `HOSTING-3`, `HEALTH-1`, `REDIRECTS-1`, `REDIRECTS-2`, `SAKADM-5`) get a title
  that says the decision with the fix of `RR-1`.

Titles of one or two words that say a decision stay, e.g. `MODEL-1` "One database.", `UX-4.1` "Confirm first.".

### `RR-3` One rule longer than 150 words

`RULE-10`: at most 150 words, unless the user agreed to a longer rule, which then ends with `(RULE-10[OK])` (`RULE-9`).
`SECURITY-3` has 162 words and no such agreement.

Fix: a shorter text (the decision that stays the rule, a second rule or text of the document for the rest), or the
agreement of the user to its length and the mark `(RULE-10[OK])`.

### `RR-4` "for example" in 5 rules

`RULE-4`: an example comes with "e.g.". `MODEL-5`, `PRIVACY-4`, `TEST-4`, `HEALTH-2`, `FRONTEND-2` say "for example".

## Needs a decision

### `RR-5` Prefixes that `RULE-3` does not allow, and ids that never change

`RULE-3`: the prefix is the name of its chapter or of its component, not shortened; one subject has one prefix. Not so:

- the desktop apps: `DESK` (SYSTEM.md, "Desktop apps") and `DESKTOP` (UI-UX.md, "Desktop apps");
- the web app: `FRONTEND` (components/INDEX.md, "Web app") and `WEB` (UI-UX.md, "Web app"); the glossary says "web app",
  not "frontend";
- sakadmin: `SAKADM` (shortened) and, in the same document, `CTL`, `UI` and `CLI`, which are not the name of the
  component; `UI` is easy to take for UI-UX.md;
- shortened or named otherwise than their chapter: `EXT` ("External systems"), `I18N` ("Texts"), `LAYER` ("Backend
  layers").

`RULE-3` also says that an id never changes. A new prefix is a new id for each of these rules, and every reference in
the code, the tests, the documents and the reviews changes with it.

Decide: keep these prefixes as they are (and say in RULES.md that the prefixes before it stay), or give them new ids as
a move (`RULE-3`: a rule that moves gets a new id, every reference changes in the same commit), one prefix at a time.

## Second pass: RULES.md itself

**Checked:** 2026-11-24, RULES.md with `RULE-1` to `RULE-7`, against its own rules and against AGENT.md, SYSTEM.md and
UI-UX.md.

## Third pass: RULES.md again

**Checked:** 2026-11-24, RULES.md with `RULE-1` to `RULE-7` and `RULE-5` at 150 words. `RR-6` is fixed: `RULE-2` allows
eight words since; `RR-7` is fixed by `RULE-8`; `RR-8` is fixed: AGENT.md no longer says what a rule is; `RR-9` is
fixed: `RULE-7` says "any longer"; `RR-10` is fixed: the description names what RULES.md has. `RR-13` is fixed: the ten
titles are shorter, `BACKUP-6` and `HOSTING-9` have a title of their own. `RR-12` is fixed: the length is `RULE-10`.
`RR-11` is fixed: an example in a rule comes with "e.g." (`RULE-4`).
