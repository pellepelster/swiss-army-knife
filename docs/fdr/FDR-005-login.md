# FDR-005: Login

**Status:** Active
**Last reviewed:** 2026-10-29

## Overview

The apps of a deployment need a session. A deployment is in one of two user modes, chosen explicitly when it is
created or deployed:

- **Single user:** one user, the admin, who signs in with the password of the deployment.
- **Multi user:** users sign in with a link by e-mail; the admin can also sign in with the password of the
  deployment.

## Behavior

- **Password login.** The admin signs in with the password of the deployment, in both modes. Only the admin can
  sign in with a password.
- **E-mail login.** Only in the multi user mode: every allowed address can sign in with a link by e-mail. The
  mails come from `noreply@<root domain>`.
- **The login page** shows what the deployment offers:
  - multi user: the e-mail login, and a link "Sign in with password";
  - single user: the password login only.
- **The admin.** Single user: the admin has no e-mail address. Multi user: the admin is the first allowed address,
  and is the same user with the same data whether signed in with the password or by e-mail.
- **Who is signed in.** The page shows the user the backend takes the session for (`GET /api/v1/auth/who-am-i`):
  `admin` for a password login, else the e-mail address.
- **Limits.** A client tries only a few passwords an hour, and the server only a few more altogether. A wrong
  password tells nothing but that it is wrong.
- **sakctl** asks for the user mode, in the window and on the command line (`--user-mode single|multi`). Multi
  user requires the e-mail address of the admin and the mail server; single user asks for neither.

## Design Decisions

### 1. The admin password is the deployment password

**Decision:** The admin signs in with the password of the deployment; there is no separate admin password.
**Why:** One password to remember for one deployment. sakctl derives everything else from it already.
**Tradeoff:** A login sends the deployment password to the server. A compromised server or a phished login gives
away the password, which is also the key to the ssh access and the cloud resources of the deployment.

### 2. Only a hash reaches the server

**Decision:** sakctl writes an argon2id hash of the password (its own salt, derived from the name) into the
environment of the backend; the backend checks a password against it.
**Why:** The password is nowhere on the server. The salt of its own keeps the hash apart from the password hash
label of the cloud resources. The salt is derived, so every deploy writes the same hash and the backend does not
restart for nothing.
**Tradeoff:** The hash is on the server; with it, the password can be guessed offline, as with the password hash
label.

### 3. The limits come before the hash

**Decision:** The limits of a client and of the server are checked before the password is hashed.
**Why:** argon2id is slow on purpose. Without the limits first, a flood of logins would keep the server busy.
**Tradeoff:** Someone can use up the attempts of an hour, and the admin cannot sign in with the password until the
hour is over. In the multi user mode the e-mail login stays.

### 4. Two explicit user modes

**Decision:** The user mode is chosen explicitly, single or multi. Multi user needs a mail server and the admin's
address; single user needs neither.
**Why:** A family or a small group may have no mail server of its own (refer to the README, "Target audience").
An explicit choice makes clear what each mode needs, instead of a mode that follows from an optional field left
empty.
**Tradeoff:** Without a mail server there is only one user.

### 5. A fixed sender

**Decision:** The login mails come from `noreply@<root domain>`; the sender is not configurable.
**Why:** One field less to ask for. The root domain is the deployment's own.
**Tradeoff:** A mail server that only sends from certain addresses needs `noreply@<root domain>` allowed.

### 6. The session knows how it was signed in

**Decision:** A session records whether it came from the password or from a link by e-mail; who-am-i names a
password session `admin`. The data of the multi user admin hangs on the address either way.
**Why:** The page shows how the user signed in, and the admin keeps one set of data.
**Tradeoff:** None known.

## Gates

- `Role.ADMIN`: the password login gives a session of the admin: the first of `auth.admins` (multi user), or
  `admin` without admins (single user).

## Related

- **ADRs:** none
- **FDRs:** FDR-001 (the hosts of the login page)

## Open Questions

- How does a password rotation reach the admin password? It is the deployment password, so a rotation changes
  both.
- A deploy does not know the user mode a deployment was created with; deploying a multi user deployment as single
  user removes its e-mail login. Should the mode be kept as a label, like the dns mode?
