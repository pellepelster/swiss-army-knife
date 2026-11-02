---
name: quick-release
description: Run all unit tests, fix what fails, commit and push everything on main, then tag the next release with `mise run release`. Only when the user asks for a quick release.
disable-model-invocation: true
---

# Quick release

Stop and tell the user at the first step that can not be finished.

1. **Test.** Run all of `mise run backend:test:unit`, `frontend:test:unit`, `cli:test:unit`, and
   `android:test` when an Android SDK is there (else say it was skipped).
2. **Fix.** Fix the cause of each failure; change a test only when its behavior changed on purpose,
   never skip or loosen one. Regenerate generated files (`mise run i18n:generate`), never edit them.
   Ask when a fix needs a decision. Repeat 1 until all pass.
3. **Commit and push** everything on `main` as one commit, a one-sentence message in the style of
   `git log` with the session's attribution lines; ask about untracked files that look like
   leftovers. A refused push: `git pull --rebase`, back to 1.
4. **Release.** `mise run release` tags and pushes the next patch version; tell the user the
   version and the run of `gh run list --workflow swiss-army-knife.yml --limit 1`.
