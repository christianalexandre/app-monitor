---
id: ADR-0081
title: What the desktop stores on disk, where, and how it is protected
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0081 — What the desktop stores on disk, where, and how it is protected

## Context

PLAN-0006 M5.1 (a). Until now the desktop holds traffic in memory and loses
it on quit (SDD 01, `TrafficStore`). M5 writes it to a SQLite file
(ADR-0005). What is stored and how it is protected is a "stop and ask" area
(`AGENTS.md`, security / privacy).

Already decided, not reopened here:

- SQLite through SQLDelight, bodies compressed in their own table
  (ADR-0005). The MCP server, when it comes (M6), binds `127.0.0.1` only.
- Masking runs on the device, before the wire: `Authorization`,
  `Proxy-Authorization`, `Cookie`, `Set-Cookie`, URL user info, URLs in
  response headers, plus what the app adds; bodies are never masked
  (ADR-0018 B, ADR-0032, ADR-0033, DEBT-0019). `masking = false` turns every
  rule off (ADR-0034). The desktop cannot tell masked traffic from
  unmasked: it receives what the device sent.
- Bodies and frame payloads are cut on the device at `maxBodyBytes`,
  1 MiB by default (ADR-0018 C, ADR-0020, ADR-0028).
- The SDK ships in debug builds only (ADR-0017, ADR-0072).
- The packaged app's bundle id is
  `io.github.christianalexandre.appmonitor.desktop`, its Linux package
  name `app-monitor` (`desktop/app/build.gradle.kts`, ADR-0055).

What reaches the desktop today (`protocol/proto/appmonitor/v1/`): `Hello`
(platform, `device_id`, `device_name`, OS version, `app_id`, app and SDK
version and name, channels), and per envelope its channel, type,
`timestamp_ms` and payload: `RequestStarted` (method, URL, headers, body,
truncation), `ResponseReceived` (status, headers, body, truncation),
`RequestFailed` (error text), `WebSocketFrame` (direction, opcode, payload,
truncation).

Evidence for the locations and primitives:

- macOS: per-user app data goes in `~/Library/Application Support/<bundle
  id>/` ([Apple, File System Programming Guide, macOS Library
  Directory Details](https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/FileSystemProgrammingGuide/MacOSXDirectories/MacOSXDirectories.html)).
- Linux: `$XDG_DATA_HOME`, default `$HOME/.local/share`
  ([XDG Base Directory Specification](https://specifications.freedesktop.org/basedir-spec/latest/)).
- Windows: `FOLDERID_LocalAppData`, `%LOCALAPPDATA%`, default
  `C:\Users\<user>\AppData\Local`
  ([KNOWNFOLDERID](https://learn.microsoft.com/en-us/windows/win32/shell/knownfolderid)).
  It is under the user's profile, readable by that user and administrators.
- Encryption at rest needs SQLCipher (BSD-3-Clause,
  [LICENSE.md](https://github.com/sqlcipher/sqlcipher/blob/master/LICENSE.md))
  and a key kept somewhere the attacker who can read the file cannot: the
  macOS Keychain, libsecret, the Windows Credential Manager — three native
  integrations.
- SQLite leaves deleted rows in free pages until they are reused or the
  file is vacuumed, unless `PRAGMA secure_delete` is on, which "overwrites
  deleted content with zeros" ([SQLite pragmas](https://www.sqlite.org/pragma.html#pragma_secure_delete)).

## Options

### A. What is written

1. **Everything the desktop receives, as received** — every `Hello` field,
   every envelope field, bodies and frame payloads byte for byte (masked or
   not, as the device sent them), plus what the desktop derives (session
   start and end, peer address, run). Undecodable or unknown envelopes are
   kept raw so a newer SDK's traffic is not lost. What you see live is what
   you see after a restart.
2. **Everything except bodies and payloads**, which stay in memory — a
   smaller file and fewer secrets on disk (bodies are never masked,
   DEBT-0019); search, export and the M7 insights lose the bodies after a
   restart.
3. **Desktop-side masking before the write** — the desktop re-applies the
   default rules even when the device sent `masking = false`; contradicts
   the developer's explicit choice (ADR-0034) and keeps two places where
   masking happens.

Recommended: 1.

### B. Where the file lives

1. **The OS per-user data directory**: `~/Library/Application
   Support/io.github.christianalexandre.appmonitor.desktop/` on macOS,
   `$XDG_DATA_HOME/app-monitor/` (default `~/.local/share/app-monitor/`) on
   Linux, `%LOCALAPPDATA%\App Monitor\` on Windows; one database file
   `appmonitor.db` with its WAL beside it. An `APPMONITOR_DATA_DIR`
   environment variable overrides it, for tests and for running two
   desktops side by side. The window shows the path in the settings.
2. **A directory the developer picks on first launch** — one more dialog
   before the first device connects.

Recommended: 1.

### C. Permissions and encryption

1. **No encryption; owner-only permissions.** On macOS and Linux the
   directory is created `0700` and the files `0600`; on Windows
   `%LOCALAPPDATA%` is already per-user. The threat this leaves open is
   someone who can read the user's files; full-disk encryption (FileVault,
   BitLocker, LUKS) is the OS's answer to that, and the same files already
   hold the developer's source and tokens.
2. **SQLCipher with a key in the OS keystore** — protects a copied file;
   three keystore integrations, a native library per OS in the package
   (ADR-0055), and a lost key loses the history.

Recommended: 1. Option 2 can follow as a setting if a team needs it.

### D. Deleted data

1. **`PRAGMA secure_delete = ON`** — rows removed by retention (ADR-0082)
   or by "clear" are zeroed on disk, at the cost of extra writes on delete
   only; "clear everything" deletes the files themselves.
2. **Default SQLite behaviour** — deleted rows readable in free pages until
   reused.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- A.1 makes the database as sensitive as the traffic: with `masking =
  false`, tokens and bodies stay on disk until retention or "clear" removes
  them. The README says so next to the masking switch.
- B.1 and C.1 are platform primitives M5.2's ADR verifies per OS
  (directory creation, POSIX permissions through `java.nio.file`).
- The MCP server (M6) reads the same store; what it exposes is decided
  there.
