---
id: ADR-0085
title: Desktop storage on SQLDelight 2.4.1 and sqlite-jdbc, its files and connection settings
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0085 — Desktop storage on SQLDelight 2.4.1 and sqlite-jdbc, its files and connection settings

## Context

PLAN-0006 M5.2. ADR-0005 chose SQLite through SQLDelight; ADR-0081 fixed
what is written, where (B.1) and how it is protected (C.1 owner-only
permissions, D.1 `secure_delete`); ADR-0082 the retention bounds. This ADR
lists the primitives the store relies on, with their evidence, before the
code (M5.3). SDD 09 is the design built on them.

Evidence, read on 2026-10-24:

- **Versions.** Maven Central `maven-metadata.xml`: `app.cash.sqldelight`
  `gradle-plugin`, `sqlite-driver`, `coroutines-extensions` and
  `sqlite-3-44-dialect` at `2.4.1` (released 2026-10-06);
  `org.xerial:sqlite-jdbc` at `3.53.4.0`. The Gradle module metadata of
  `sqlite-driver:2.4.1` requires `org.xerial:sqlite-jdbc:3.53.4.0` at
  runtime. Dialects in 2.4.1 go up to `sqlite-3-44-dialect`
  ([SQLDelight Gradle docs, 2.4.1](https://github.com/sqldelight/sqldelight/blob/2.4.1/docs/common/gradle.md)).
- **License.** `sqlite-jdbc` POM: Apache-2.0. SQLDelight: Apache-2.0.
- **Native library.** The default `sqlite-jdbc-3.53.4.0.jar` (12 MB) holds
  `libsqlitejdbc.dylib` for `Mac/x86_64` and `Mac/aarch64`,
  `libsqlitejdbc.so` for `Linux/x86_64` and `Linux/aarch64`, and
  `sqlitejdbc.dll` for `Windows/x86_64` and `Windows/aarch64` (`unzip -l`),
  every target the desktop packages for (ADR-0055). At first use the
  matching library "is automatically extracted into your OS's temporary
  folder" ([README](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/README.adoc));
  `org.sqlite.tmpdir` moves it, `org.sqlite.lib.path` / `org.sqlite.lib.name`
  load one already on disk
  ([USAGE.md](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/USAGE.md)).
- **JDK modules.** The jar's `module-info` `requires transitive java.sql`
  (and `java.sql.rowset`, which no class in the jar references:
  `grep -rl javax/sql/rowset` over its classes is empty); `slf4j` is
  `requires static`, optional in the POM. The packaged runtime today has
  `java.instrument`, `java.management`, `jdk.unsupported`
  (`desktop/app/build.gradle.kts`), not `java.sql`.
- **Connections.** `JdbcSqliteDriver` with a file URL uses a
  `ThreadedConnectionManager`: one connection per thread, and outside a
  transaction each statement opens and closes its own connection
  (`JdbcSqliteDriver.kt`, `JdbcDriver.kt` at tag 2.4.1). A `PRAGMA` run as a
  statement is therefore lost with its connection; per-connection settings
  must be JDBC properties. `SQLiteConfig.Pragma` accepts `journal_mode`,
  `synchronous`, `foreign_keys`, `secure_delete`, `busy_timeout` as
  properties, not `auto_vacuum`.
- **Schema versions.** The `JdbcSqliteDriver(url, properties, schema)`
  factory creates the schema when `PRAGMA user_version` is 0, migrates
  when it is lower than `schema.version`, and does nothing when it is
  higher (`JdbcSqliteSchema.kt` at 2.4.1). `verifyMigrations` fails the
  build on a broken migration; `schemaOutputDirectory` enables the tasks
  that check migrations against the stored `.db` schemas (Gradle docs
  above).
- **File modes.** SQLite creates WAL and journal files "using the same
  permissions as the associated database file"
  ([os_unix.c, `findCreateFileMode`](https://github.com/sqlite/sqlite/blob/master/src/os_unix.c)).
- **Free pages.** `auto_vacuum` can only change on an empty database
  without `VACUUM`; `INCREMENTAL` lets `PRAGMA incremental_vacuum` return
  free pages to the OS ([SQLite pragmas](https://www.sqlite.org/pragma.html#pragma_auto_vacuum)).
- **Data directories.** Java has no API for them; ADR-0081 cites the
  macOS, XDG and Windows sources. XDG: when `$XDG_DATA_HOME` "is either not
  set or empty, a default equal to `$HOME/.local/share` should be used",
  and a relative path is invalid and ignored.

## Options

1. **SQLDelight 2.4.1 on its `sqlite-driver`, with the sqlite-jdbc it
   brings (3.53.4.0)** — the native libraries for every desktop target in
   one Apache-2.0 jar, no build step of ours; 12 MB in the package and an
   extraction to the temporary folder at startup.
2. **Same, with the per-OS `natives-*` classifier jars** — about 2 MB per
   package instead of 12, but three dependency graphs, one per package job,
   for a size the release does not care about yet.
3. **SQLDelight on Android's or a KMP `bundled` SQLite driver** — none of
   them targets the desktop JVM in 2.4.1; ADR-0005 needs JVM only.

## Decision

Option 1. The store is built on:

- `app.cash.sqldelight` Gradle plugin, `sqlite-driver` and
  `coroutines-extensions` `2.4.1`, dialect `sqlite-3-44-dialect` (the
  newest; the bundled engine is 3.53). `verifyMigrations = true` and
  `schemaOutputDirectory = src/main/sqldelight/databases`, the `.db`
  schemas committed.
- The default `sqlite-jdbc` jar, its native library extracted to
  `java.io.tmpdir` as the library does; no `org.sqlite.*` property.
- `java.sql` added to `nativeDistributions.modules`.
- The data directory of ADR-0081 B.1, resolved in this order:
  `APPMONITOR_DATA_DIR` if set and not empty; macOS
  `user.home/Library/Application Support/io.github.christianalexandre.appmonitor.desktop`;
  Linux `$XDG_DATA_HOME/app-monitor` when absolute, else
  `user.home/.local/share/app-monitor`; Windows `%LOCALAPPDATA%\App Monitor`,
  else `user.home\AppData\Local\App Monitor`. The OS comes from `os.name`.
- On a POSIX file system the directory is created and then set to `0700`
  with `Files.setPosixFilePermissions` (a creation attribute is subject to
  the umask), and `appmonitor.db` is created empty with `0600` before
  SQLite opens it, so its WAL and shared-memory files inherit `0600`.
- Connection properties on every connection: `journal_mode=WAL`,
  `synchronous=NORMAL`, `foreign_keys=true`, `secure_delete=true`,
  `busy_timeout=5000`.
- On an empty file (`user_version` 0) `PRAGMA auto_vacuum = INCREMENTAL`
  runs before the schema is created. The store opens the driver without
  the schema factory and applies its rule itself: create at 0, migrate
  when lower, **refuse to open** when higher (a database written by a newer
  App Monitor).
- Bodies and frame payloads compressed with `java.util.zip.Deflater`
  (`java.base`, no dependency), stored raw when deflating does not shrink
  them; a codec column says which.

## Consequences

- The package grows by about 12 MB; Option 2 stays open if size matters at
  release (M10).
- The native library lands in the temporary folder on every start; a
  `noexec` `/tmp` on Linux would fail to load it, which `org.sqlite.tmpdir`
  fixes. The unsigned macOS package (ADR-0055) loading an extracted dylib is
  checked by M5.9's packaged run, not assumed here.
- `make check` grows by the `desktop/storage` tests and the migration
  verification task (M5.3 measures it).
- A downgrade shows an error instead of reading a schema it does not know;
  "Clear all data" (ADR-0082 D) is the way out.
