---
id: ADR-0032
title: What the SDK masks in a URL's user info
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0032 — What the SDK masks in a URL's user info

## Context

What the SDK captures and masks is a "stop and ask" area (`AGENTS.md`,
security and privacy). ADR-0018 B masks credential headers by default and
query parameters on request; M1.R3 added that a URL password is always sent
as `██` with the user name kept (SDD 05). The second M1 milestone review
(PLAN-0002, M1.R4) found that the user name itself is often the secret, and
that M1.R3 changed what is masked without an ADR. This ADR records both.

Already decided, not reopened here:

- Masking runs on the device, before the wire (ADR-0018 A).
- The mask value is `██`, the field name kept (ADR-0018 B). Its
  percent-encoding in the URL is DEBT-0033.
- Query parameters are masked only when named in `maskedQueryParameters`
  (ADR-0018 B).

Evidence, checked on 2026-10-23:

- Stripe: "Provide your API key as the basic auth username value. You don't
  need to provide a password." (`curl https://api.stripe.com/v1/charges -u
  sk_test_...:`, <https://docs.stripe.com/api/authentication>).
- GitHub: "use an OAuth token for the username and either a blank password
  or the string `x-oauth-basic`", e.g. `https://<token>@github.com/...`
  (<https://github.blog/2012-09-21-easier-builds-and-deployments-using-git-over-https-and-oauth/>).
  So a token may sit in the user name with no password, an empty one, or a
  fixed non-secret one.
- RFC 3986 §3.2.1: "Use of the format "user:password" in the userinfo field
  is deprecated", and applications "should not render as clear text any data
  after the first colon" (<https://www.rfc-editor.org/rfc/rfc3986#section-3.2.1>).
  The RFC protects the password only; it predates tokens as user names.
- Today `Masking.url` (`sdk/android-okhttp/.../Capture.kt`) replaces a
  non-empty password and returns the user name untouched.

## Options

1. **Mask the whole user info**: a non-empty user name and a non-empty
   password both become `██`. `https://ghp_x@github.com/` →
   `https://██@github.com/`; `https://alice:pw@host/` → `https://██:██@host/`.
   No secret in user info reaches the desktop, whatever the scheme. The
   developer still sees that credentials were sent, not which user. User
   info in an app's HTTP URL is rare, so little debugging value is lost.
2. **Keep the user name, mask the password** (today, M1.R3). Matches RFC
   3986 and lets the developer tell users apart; sends the Stripe and GitHub
   tokens above in clear, while the same token in an `Authorization` header
   is masked by default.
3. **Mask the user name only when the password is empty**: covers
   `token@host` and `sk_live_x:@host`, keeps `alice:pw@host`'s name. A
   heuristic: `ghp_x:x-oauth-basic@github.com` still leaks the token, and
   the rule is harder for every SDK (M2 iOS) to reproduce exactly.

Recommended: 1. It is the only option where no known token scheme leaks, and
it is the same rule as headers: the name of the field is kept (the `@` shows
user info was there), the value is not. An option to keep user names can
follow if a developer asks for it.

## Decision

The owner accepted option 1: a non-empty user name and a non-empty password
in a URL's user info are both sent as `██`; an empty one stays empty. M1.R4
applies it in `Masking.url`, SDD 05 and the `:sdk:android-okhttp` tests.

## Consequences

- Option 1 or 3: `Masking.url` gains the user name rule; SDD 05's masking
  bullet changes; the M1.R3 test keeps its password assertion and its user
  name assertion follows the choice.
- Option 2: M1.R4 closes with this ADR only; the tokens above stay in clear
  and the review finding is rejected with this ADR as the reason.
- Whatever is chosen becomes part of the shared masking rule the iOS SDK
  (M2) reproduces (ADR-0018 A).
