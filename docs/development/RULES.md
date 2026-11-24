---
title: "Rules"
description: "How a rule of the documents for developers is made and written: what it is, its form, title, id, words and length, how code and tests name it, how it is deprecated, and how an exemption is marked."
weight: 22
---

# Rules

How a rule of `docs/development/` is made and written. The rules of this document have the prefix `RULE`.

- `RULE-1` **A rule is a decision with an id.** A rule is one decision on how SAK is built or how it behaves, which
  the code, the tests and the documents follow until the rule changes. Text that only explains or describes is no
  rule. A rule is written once; other text refers to it by its id.
- `RULE-2` **The title is the rule in short.** The title says the decision in two to eight words, e.g. **Soft delete
  only.**, not only its subject, e.g. not **Deletes.**
- `RULE-3` **An id is a name for good.** The prefix of an id is the name of its chapter or of its component, in capitals
  and not shortened; one subject has one prefix in all documents. A new rule gets the next number of its prefix. An id
  never changes and is never used again, also when its rule is deleted. A rule that moves to another document gets a new
  id there, and every reference to it changes in the same commit; a rule keeps its id when its prefix stays the subject
  of the document it moves to.
- `RULE-4` **Plain words in the present tense.** A rule is written in ASD-STE100 Simplified Technical English, with
  the words of the glossary. It says what is true, in the present tense and the active voice, e.g. "The backend keeps
  …", not "The backend should keep …". "Never" says what is forbidden. A rule does not use "should"; an example in a
  rule comes with "e.g.".
- `RULE-5` **One decision for each rule.** A rule says one decision, and why when the reason is not clear.
- `RULE-6` **Code and tests name their rule.** Code that does what a rule says refers to it by its id in a comment, and
  a test that checks a rule has the id in its name, e.g. `(UX-13)`.
- `RULE-7` **A deprecated rule is struck through.** A rule that no longer holds, but that code or text still follows,
  is deprecated: its title starts with "Deprecated:", and the rest of its title and its text are struck through, e.g.
  ``- `MODEL-1` **Deprecated: ~~One database.~~** ~~The backend keeps …~~`` It names the rule that replaces it, if
  there is one, after its text. A deprecated rule keeps its id, and it is deleted when nothing follows it any longer.
- `RULE-8` **One bullet for each rule.** A rule is one bullet: its id in backticks, its title in bold with a full
  stop, then its text, e.g. ``- `MODEL-3` **Soft delete only.** No row is ever deleted for real.`` A part of a rule
  that other text refers to is a bullet below it, with the id of its rule, a full stop and a number, e.g. `UX-4.1`.
- `RULE-9` **An exemption is marked at the end.** A rule that does not follow one of these rules needs the explicit
  agreement of the user for it, and its text ends with the id of the rule it does not follow and "[OK]" in brackets,
  e.g. `(RULE-5[OK])`; one mark for each rule it does not follow.
- `RULE-10` **A rule has at most 150 words.** A longer rule needs an exemption (`RULE-9`).
