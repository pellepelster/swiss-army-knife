# Review findings

Review of the console, its api and the log pipeline (backend and ui) on 2026-10-26, looking for
missing edge case handling, security issues and simplifications. Every finding is fixed in a
commit of its own, the status column tracks them.

| # | Severity | Status | Finding |
|---|---|---|---|
| 1 | medium | fixed | anyone can lock the admin out of logging in |
| 2 | medium | fixed | no request body limit |
| 3 | medium | fixed | metrics group by raw, attacker-controlled `user_agent` |
| 4 | medium | open | sessions survive removal from `allowed_emails` |
| 5 | low | open | login timing leaks whether an address is allowed |
| 6 | low | open | sse keepalive can drop an entry |
| 7 | low | open | live tails pin io threads with no cap |
| 8 | low | open | malformed responses escape as 500s, newline in `q` becomes a 503 |
| 9 | low | open | search has no time bound |
| 10 | low | open | login tokens can reach caddy's error log |
| 11 | low | open | console host proxies the whole controller, no hsts |
| 12 | low | open | jwt defense in depth (`exp`, `kid`) |
| 13 | low | open | bucket index truncates toward zero |
| 14 | low | open | a console setup failure stops the whole controller |
| 15 | medium | open | ui: live view hides errors |
| 16 | low | open | ui: search debounce timer outlives the component |
| 17 | low | open | ui: live view updates per entry |
| 18 | low | open | ui: unknown site in the url |
| 19 | low | open | ui: stored session trusted without validation |
| 20 | low | open | ui: sites never retried after a network error |
| S1 | - | open | duplicate stream filter builders |
| S2 | - | open | duplicate json line decoding |
| S3 | - | open | dead key rotation plumbing |
| S4 | - | open | redundant `redirect` in the login link |
| S5 | - | open | `SiteChoice` wrapper and duplicate openapi path |
| S6 | - | open | `SESSION_SECURITY_SCHEME` alias |

## Backend

### 1. Anyone can lock the admin out of logging in (medium, fixed)

`AuthRoutes.kt`: the per-address limit was a hard cap of five links per hour keyed only by the
address, and the admin address is committed in `config/controller-config.yaml`. Posting
`/api/v1/auth/login` for it five times an hour, well under the per-client cap, meant the admin got
`202` but no mail, with nothing logged.

Fix: the limit is per address *and* client, there is a ceiling of links per hour for the smtp
server, and an allowed address that asks in vain is logged.

### 2. No request body limit (medium, fixed)

`AuthRoutes.kt` `receive<LoginRequest>()`: neither ktor nor caddy limited request bodies, so an
unauthenticated client could stream a multi-GB json `email` and OOM the controller, which then
restart-loops (and loses the in-memory rate limiter state).

Fix: `RequestBodyLimit` of 16KiB on `/api` (413 before the body is read), `request_body
max_size` of 1MiB on the console route in caddy.

### 3. Metrics group by raw, attacker-controlled `user_agent` (medium, fixed)

`SiteMetricsService.kt` `stats by (user_agent)`, `UserAgentGroup.kt`, `vector.yaml`: any visitor
of any site controls the header (caddy accepts ~1MB), vector ships it verbatim and victorialogs
answers with one series per distinct value, parsed into memory on every metrics load. The
`Version/[\d.]+ .*Safari` rule is quadratic in java's regex: one request with a 600KB user agent
of repeated `Version/1 ` costs seconds of cpu on every page view for the whole retention.

Fix: truncate `user_agent` (512) and `request.uri` (2048) in vector, cap the classifier's input.

### 4. Sessions survive removal from `allowed_emails` (medium)

`AuthRoutes.kt` `session()`: only `/exchange` re-checks the allowlist, a 30 day session jwt is
valid by signature alone. Editing the config does not revoke anyone, only deleting the signing key
does, which logs out everyone.

Fix: the session validation checks `mayLogin` too, so the allowlist doubles as revocation list.

### 5. Login timing leaks whether an address is allowed (low)

`AuthRoutes.kt` `/login`: the ecdsa signing of the link runs synchronously only for allowed
addresses, the comment claims otherwise. Sub-millisecond, but it is the one difference the design
tries to avoid.

Fix: allowlist check, token and mail all move into the background coroutine.

### 6. SSE keepalive can drop an entry (low)

`SiteRoutes.kt` `withTimeoutOrNull { received.receiveCatching() }`: an element is lost when the
timeout cancels a receive that already took it (documented kotlinx behaviour). Rare, real.

Fix: `select { onReceiveCatching; onTimeout }`, whose clauses are atomic.

### 7. Live tails pin io threads with no cap (low)

`SiteRoutes.kt` `launch(Dispatchers.IO)`, `VictoriaLogsClient.kt` `timeout = null`: 64 shared
threads, ~60 open tabs starve mail sending and metrics. The `null` timeout is unnecessary, the jdk
request timeout only covers the headers, not the streaming body.

Fix: a semaphore of concurrent tails (503 above), the default header timeout for the tail.

### 8. Malformed responses escape as 500s, newline in `q` becomes a 503 (low)

`VictoriaLogsClient.kt`: a non-object `data` or a bad json line throws outside the `try`, a bad
tail line kills the whole stream. `SiteLogs.kt`: `q=a%0Ab` makes victorialogs answer 400 (go's
`strconv.Unquote` rejects the newline), logged as a warning with stack trace on every call.

Fix: control characters in `q` are rejected with 400, parsing is wrapped uniformly, a bad tail
line is skipped.

### 9. Search has no time bound (low)

`SiteLogs.kt`: the regex runs over the full retention to find 500 rows.

Fix: the search is bounded to the longest statistics range (30 days).

### 10. Login tokens can reach caddy's error log (low)

`sak-ingress/Caddyfile`: the access log deletes `token`, `log default` is plain json. If the
controller restarts while the admin clicks the link, the 502 error entry stores
`/signin?token=…` unredacted.

Fix: the same query filter on the default log.

### 11. Console host proxies the whole controller, no hsts (low)

`SakCaddy.kt`: the console route is host-based only, so `https://console.<root>/s3/…` reaches the
s3 api outside the security headers. Harmless (sigv4) but needless. No
`Strict-Transport-Security` anywhere.

Fix: `/s3*` is not served on the console host, the console route sets hsts.

### 12. JWT defense in depth (low)

`TokenService.kt`: java-jwt validates `exp` only if present, a signed login token without one
NPEs in `putIfAbsent` and is never marked used. Only reachable with the private key. The `kid`
is emitted but no verifier reads it (see S3).

Fix: a login token without `exp` is rejected.

### 13. Bucket index truncates toward zero (low)

`MetricsRange.kt`: `/` truncates toward zero, a point in `(start-step, start)` lands in bucket 0
instead of being dropped. Not triggered today, victorialogs aligns to `start`.

Fix: `Math.floorDiv`.

### 14. A console setup failure stops the whole controller (low)

`SakController.kt`: a corrupt `signing-key.pem` or a bucket named like a reserved site throws
inside `module()`, so the s3 api does not start either.

Fix: the console failing to set up is logged as an error and the controller runs without it.

## UI

### 15. Live view hides errors (medium)

`live-logs.ts`: on any failure other than 401 (e.g. victorialogs down, 503) the view reconnects
every 3 seconds forever and shows "Reconnecting" with no message, the `error` signal is never set
on this path.

Fix: the failure is shown, the reconnects back off.

### 16. Search debounce timer outlives the component (low)

`live-logs.ts`: type in the search, click "Statistics" within 300ms, and the timer navigates with
`?q=` merged onto the statistics url.

Fix: cleared on destroy.

### 17. Live view updates per entry (low)

`live-logs.ts`: a five minute backlog of thousands of entries arrives in a burst and triggers
change detection per entry, with a new array each time.

Fix: entries are emitted and prepended per chunk.

### 18. Unknown site in the url (low)

`site-metrics.ts`: `/logs/statistics/nope` gets a 404 but shows "the logs may not be available
right now", and the dropdown has no selected option.

Fix: an unknown site is reported as such.

### 19. Stored session trusted without validation (low)

`auth.service.ts`: `Date.parse('garbage')` is NaN, `NaN <= now` is false, so a corrupted stored
session counts as valid until the server answers 401.

Fix: a session with an unreadable expiry is discarded on load.

### 20. Sites never retried after a network error (low)

`sites.service.ts`: `load()` gives up after one failure, sidebar and pages stay empty until a
reload.

Fix: retried with a delay, not after a 401.

## Simplifications

- S1 `SiteLogs.kt` and `SiteMetricsService.kt` build `_stream:{site="…"}` with two hand-written
  escapers; one `streamFilter`/`quoted` in the logs package.
- S2 `VictoriaLogsClient.kt` decodes json lines twice (`record` with error wrapping, `LogTail.next`
  without); `LogTail` reuses `record`.
- S3 `SigningKey.keyId`/`withKeyId` are never consulted by a verifier; dropped until rotation
  exists.
- S4 `AuthRoutes.kt` appends `&redirect=…` to the login link, the signin page ignores it and uses
  the claim from `/exchange`; dropped, which keeps the value out of logs and history.
- S5 `SiteChoice` only encodes "all vs not found"; `/swagger/openapi.json` duplicates
  `/doc/openapi.json`.
- S6 `SESSION_SECURITY_SCHEME = SESSION_AUTH` is an alias with no purpose.

## Verified sound

LogsQL quoting and re2 escaping are correct and pinned by tests, site ids never come from
requests. The jwt algorithm is pinned (`none`/HS256 rejected), issuer and `token_use` are
required, replay is blocked atomically, `safeRedirect` is enforced on both ends,
`X-Forwarded-For` last-hop is right because caddy overwrites it and the controller binds
127.0.0.1. The key file is 0600 via atomic move. CSP, no-referrer, `replaceState` of the token,
bearer instead of cookie (no csrf), container hardening, smtp tls enforcement, and vector dropping
every header except the user agent all check out. The e-mail body can not be used for header
injection.
