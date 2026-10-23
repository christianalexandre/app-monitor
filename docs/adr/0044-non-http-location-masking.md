---
id: ADR-0044
title: Whether a custom-scheme Location gets the URL masking rule
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0044 — Whether a custom-scheme Location gets the URL masking rule

## Context

ADR-0033 masks `Location` and `Content-Location` by resolving them against
the request URL and applying the request URL rule; a value that does not
resolve to an `http` or `https` URL is sent as received (DEBT-0035). A
mobile OAuth redirect to the app's own scheme
(`Location: com.example.app:/oauth?code=abc`, RFC 8252 §7.1) therefore
reaches the desktop with `code` in clear, even when `code` is in
`maskedQueryParameters`. Both SDKs ship that behaviour, pinned by the case
`location that is not an http url` of `protocol/fixtures/masking.json`
(ADR-0036 A.1). What the SDKs mask is a "stop and ask" area (`AGENTS.md`);
DEBT-0035's trigger fired in PLAN-0003 M2.7.

Already decided, not reopened here:

- The URL rule: user name and password `██`, `maskedQueryParameters` in the
  query and in a `name=value&…` fragment (ADR-0032, ADR-0033).
- `masking = false` turns every rule off (ADR-0034).
- `maskedHeaders` can mask a whole header, `Location` included (ADR-0018 B).
- Both SDKs apply the same rule, pinned by the shared vector (ADR-0036 A.1).

Evidence, run on 2026-10-23 (scripts under `build/m27a/`, not committed):

- **The value reaches the capture on Android with the default install.**
  OkHttp 5.3.2 `RetryAndFollowUpInterceptor.buildRedirectRequest`:
  `val url = userResponse.request.url.resolve(location) ?: return null`,
  commented "Don't follow redirects to unsupported protocols"; a null
  follow-up returns the `302` to the caller
  (<https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt>).
  So an application interceptor, not only a network one, captures the
  `302` and its custom-scheme `Location`. DEBT-0035's "the app never
  follows such a `Location`" holds, but the capture still sees it.
- **On iOS it reaches the capture when the app refuses the redirect.**
  A `URLSession` with no delegate follows a `302` to
  `com.example.app:/oauth?code=abc` and fails with `NSURLErrorDomain -1002`
  "unsupported URL" (macOS 26 host, `swift` script against a local server);
  the capture's `RequestFailed` keeps domain, code and description only
  (`describe`, SDD 07), so nothing leaks there. A delegate that passes `nil`
  to `willPerformHTTPRedirection` gets the `302` with its `Location` as the
  final response (same script); `Masking.headers`' scheme guard then sends
  that `Location` as received (read from the code, not run in a test).
- **Parsers.** RFC 3986 §3 gives every URI a query after the first `?` and a
  fragment after the first `#`, whatever the scheme; user info exists only
  after `//`. Tested on `com.example.app:/oauth?code=abc`,
  `com.example.app://oauth?code=abc#access_token=t`, `myapp:oauth?code=abc`
  and `urn:ietf:wg:oauth:2.0:oob?code=x`:
  - Foundation `URLComponents` splits scheme, host, query and fragment for
    all four, as RFC 3986 says; the iOS `Masking.url` already masks them if
    the `http`/`https` guard is lifted.
  - `java.net.URI` (JDK 21) splits the first two; it reads the last two as
    opaque (RFC 2396), query `null`. OkHttp's `HttpUrl` parses none.

## Options

1. **Every absolute URI gets the URL rule, by RFC 3986 generic syntax.** A
   `Location` or `Content-Location` that resolves to `http` or `https` is
   masked as today; any other value with a scheme is masked as written:
   `maskedQueryParameters` in the text after the first `?` (up to `#`) and
   in a `name=value&…` fragment, user name and password `██` when there is
   a `//` authority. Only the masked spans change, the rest is sent byte
   for byte (no normalisation). iOS drops the scheme guard and reuses
   `URLComponents`; Android adds a small splitter for non-`http` values,
   since neither `HttpUrl` nor `java.net.URI` gives the query of
   `myapp:oauth?code=abc`. Covers the RFC 8252 forms and `urn:` /
   opaque forms alike; one more parsing path on Android.
2. **Hierarchical custom schemes only** (`scheme:/…` and `scheme://…`).
   Covers RFC 8252 §7.1's examples; Android uses `java.net.URI`, no
   hand-written splitter. `myapp:oauth?code=abc` stays in clear, and the two
   SDKs need a rule for what "hierarchical" means that `URLComponents` does
   not expose.
3. **No change.** Custom-scheme values stay as received; SDD 05 and SDD 07
   tell the developer to add `Location` to `maskedHeaders`, which masks the
   whole value, `http` redirects included. DEBT-0035 closes as won't fix
   with this ADR as the reason.

Recommended: 1. The code the developer listed in `maskedQueryParameters` is
the one an OAuth redirect to the app carries; on Android it reaches the
desktop's SQLite and MCP with the default install. Option 2 leaves the same
code in clear one character away (`myapp:oauth` vs `myapp:/oauth`).

## Decision

Option 1, taken by the owner: every absolute URI in `Location` or
`Content-Location` gets the URL rule by RFC 3986 generic syntax, in both
SDKs. Whoever wants the value in clear has `masking = false` (ADR-0034).

## Consequences

- Option 1 or 2: the fixture case `location that is not an http url`
  expects `code=██`, with a second case for the opaque or authority form;
  `aLocationThatIsNotAnHttpUrlIsSentAsReceived` becomes a masking test;
  SDD 05 and SDD 07 drop "sent as received"; DEBT-0035 is paid. Option 1
  adds a non-`http` splitter to Android's `Masking`.
- Option 3: no code change; SDD 05 and SDD 07 name `maskedHeaders` as the
  way to mask a custom-scheme redirect; DEBT-0035 closes.
- `masking = false` sends every value as received under any option.
