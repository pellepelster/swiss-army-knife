---
title: "Feature Decision Records"
linkTitle: "Features"
description: "One record for each feature: what it does, and the decisions that shaped it."
weight: 40
---

# Feature Decision Records

This directory contains Feature Decision Records (FDRs) for SwissArmyKnife. Each FDR documents one feature: what it does
behaviorally, what design decisions shaped it, and the rationale behind those decisions.

FDRs are siblings of the Architecture Decision Records (ADRs); the repository has none yet. ADRs cover cross-cutting architectural
choices; FDRs cover individual features and the design decisions specific to them.

See [`.agents/skills/fdr/SKILL.md`](../../../.agents/skills/fdr/SKILL.md) for the FDR template, writing style, and
maintenance workflow.

## Features

| #                                                           | Feature                               | Status       | Last reviewed |
|-------------------------------------------------------------|---------------------------------------|--------------|---------------|
| [FDR-001](FDR-001-dns-mode.md)                              | DNS mode                              | Active       | 2026-10-28    |
| [FDR-002](FDR-002-sqlite-encryption.md)                     | SQLite database encryption            | Planned      | 2026-10-29    |
| [FDR-003](FDR-003-html-login-emails.md)                     | HTML login e-mails                    | Planned      | 2026-10-28    |
| [FDR-004](FDR-004-maintenance-cleanup.md)                   | Maintenance cleanup                   | Planned      | 2026-10-29    |
| [FDR-005](FDR-005-login.md)                                 | Login                                 | Active       | 2026-10-29    |
| [FDR-006](FDR-006-provisioning-events.md)                   | Provisioning events                   | Planned      | 2026-10-29    |
| [FDR-007](FDR-007-backup.md)                                | Backup and restore                    | Active       | 2026-10-29    |
