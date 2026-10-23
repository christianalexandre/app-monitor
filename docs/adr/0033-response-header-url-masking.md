---
id: ADR-0033
title: Which response headers the SDK masks as URLs
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0033 — Which response headers the SDK masks as URLs

## Context

What the SDK captures and masks is a "stop and ask" area (`AGENTS.md`,
security and privacy). The request URL loses its user info (ADR-0032) and
the query parameters named in `maskedQueryParameters` (ADR-0018 B). Response
headers are masked by name only: the defaults plus `maskedHeaders` get `██`,
every other value is sent as received (`Masking.headers`,
`sdk/android-okhttp/.../Capture.kt`). The third M1 milestone review
(PLAN-0002, M1.R5) found that a header whose value is a URL carries the same
secrets the request URL is masked for.

Already decided, not reopened here:

- Masking runs on the device, before the wire (ADR-0018 A).
- The URL rule itself: user name and password `██`, named query parameters
  `██` (ADR-0018 B, ADR-0032). Its percent-encoding is DEBT-0033.
- An app can already mask a whole header by adding its name to
  `maskedHeaders` (ADR-0018 B).

Evidence, checked on 2026-10-23:

- OkHttp: application interceptors "don't need to worry about intermediate
  responses like redirects", network interceptors are "able to operate on
  intermediate responses like redirects and retries"
  (<https://github.com/square/okhttp/blob/master/docs/features/interceptors.md>).
  So `AppMonitorInterceptor` sees a `3xx` and its `Location` when installed
  with `addNetworkInterceptor`, or with `followRedirects(false)`.
- RFC 9110 §10.2.2: `Location = URI-reference`; §8.7:
  `Content-Location = absolute-URI / partial-URI`. Either may be relative
  (`/cb?code=x`)
  (<https://www.rfc-editor.org/rfc/rfc9110>).
- RFC 6749 §4.1.2: the authorization code goes "to the query component of
  the redirection URI" (`Location: https://client.example.com/cb?code=…`);
  §4.2.2: the implicit grant's access token goes "to the fragment component"
  (`Location: http://example.com/cb#access_token=…`)
  (<https://www.rfc-editor.org/rfc/rfc6749>). The query rule does not reach
  a fragment, in the request URL either.
- RFC 9110 §4.2.4: a sender "MUST NOT generate the userinfo subcomponent",
  so user info in `Location` is rare but not impossible.

## Options

1. **`Location` and `Content-Location` get the request URL rule.** Each
   value is resolved against the request URL (`HttpUrl.resolve`), masked by
   `Masking.url`, and sent masked; a relative value is sent resolved
   (absolute), a value that does not parse is sent as received. Covers the
   OAuth code flow. Costs one parse per such header, only when present.
2. **Option 1, plus the fragment.** A fragment written as `name=value&…`
   gets the same `maskedQueryParameters` rule, in these headers and in the
   request URL. Also covers the implicit grant's `access_token`. One more
   rule every SDK (M2 iOS) reproduces; a fragment that is not form-encoded
   stays untouched.
3. **No change.** Response headers stay masked by name only; an app that
   follows redirects in a network interceptor adds `Location` to
   `maskedHeaders` and loses the whole value. The finding is rejected with
   this ADR as the reason; SDD 05 says it.

Not covered by any option, recorded so it is not forgotten: `Link` (a list
of `<URI>; rel=…`) and the request's `Referer`, which can carry a page URL
with its query. Each can follow under its own ADR if a case shows up.

Recommended: 2. The same token is masked in the request URL and leaks one
hop later in a redirect under option 1 when the server uses the implicit
grant; the fragment rule is a few lines and closes it everywhere at once.

## Decision

The owner accepted option 2: `Location` and `Content-Location` get the
request URL rule, and a form-encoded fragment gets the
`maskedQueryParameters` rule, in these headers and in the request URL. The
owner also asked for a switch that turns every masking rule off (ADR-0034).

## Consequences

- Option 1 or 2: `responseReceived` maps the two headers through
  `Masking.url`; SDD 05's masking bullet changes; the M1.R5 test sends a
  `302` with a `Location` carrying user info and a masked query parameter
  (and a fragment, option 2). The PR pays DEBT-0033's and DEBT-0034's
  `Capture.kt` items.
- Option 3: M1.R5 closes with this ADR only; SDD 05 names `Location` as a
  header an app masks with `maskedHeaders`.
- Whatever is chosen becomes part of the shared masking rule the iOS SDK
  (M2) reproduces (ADR-0018 A).
