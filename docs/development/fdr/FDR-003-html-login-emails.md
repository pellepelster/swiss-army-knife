---
title: "FDR-003: HTML Login E-Mails"
linkTitle: "HTML Login E-Mails"
description: "The login mail as HTML, with the plain text as a fallback."
weight: 3
---

# FDR-003: HTML Login E-Mails

**Status:** Planned
**Last reviewed:** 2026-10-28

## Overview

The login of the apps sends a link by e-mail. Today the mail is plain text only. This feature sends the login mail as
HTML, with the plain text as a fallback, so that the link is a clear button and the mail looks like Swiss Army Knife.

## Behavior

- The login mail has an HTML part and a plain text part with the same content. A mail client that shows no HTML shows
  the plain text.
- The HTML part shows the name of the deployment, one button with the login link, how long the link is valid, and
  what to do when the user did not ask for the mail.
- The link is also shown as text, for a client that does not show the button.
- The subject does not change ("Sign in to `<base domain>`").

## Design Decisions

### 1. Plain text stays

**Decision:** The mail is `multipart/alternative`, the plain text part first.
**Why:** Some mail clients and users show plain text only. The login must work for them.
**Tradeoff:** Two versions of each text to keep the same.

### 2. Nothing is loaded from outside the mail

**Decision:** No remote images, fonts or styles, and no tracking. All styles are inline in the HTML.
**Why:** A remote resource tells a third party that the mail was opened, and from which IP address (refer to
"Privacy" in [SYSTEM.md](../architecture/SYSTEM.md)). Many mail clients also block remote resources.
**Tradeoff:** A logo must be embedded in the mail, or there is no logo.

## Related

- **ADRs:** none
- **FDRs:** none

## Open Questions

- Does the mail show the icon of Swiss Army Knife, embedded as an inline attachment (`cid:`), or no image at all?
- Does the HTML follow the colors of `sak-theme`, and does it support the dark mode of mail clients?
- Is the mail in the language of the user, and where does the backend get that language from?
