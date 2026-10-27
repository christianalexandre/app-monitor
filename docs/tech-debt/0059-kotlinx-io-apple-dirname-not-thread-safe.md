---
id: DEBT-0059
title: kotlinx-io's Path.parent and Path.name are not thread-safe on Apple
status: Open
date: 2026-10-24
trigger: The `kotlinx-io` version in `libs.versions.toml` changes, or a PR adds `Path.parent`, `Path.name` or `createDirectories` to `sdk/core/src/commonMain` or `sdk/core/src/iosMain`
paid_date:
---

# DEBT-0059 — kotlinx-io's Path.parent and Path.name are not thread-safe on Apple

## What is missing

kotlinx-io 0.9.1 computes `Path.parent` and `Path.name` on Apple with
`dirname(3)` and `basename(3)`, which return a shared buffer (ADR-0079).
`sdk/core` avoids them in `deviceId` only, through `createParentDirectories`
in `Platform.ios.kt`. Symptom of a new use: on iOS, a path operation from two
threads at once acts on the wrong directory, or fails with "No such file or
directory" for a directory that exists.

## Why it was accepted

`deviceId` was the only `sdk/core` code calling them, so the fix is one
function. The fix belongs in kotlinx-io (`dirname_r`, `basename_r`); no
upstream issue was found with `gh search issues --repo Kotlin/kotlinx-io
dirname`.

## Trigger

The `kotlinx-io` version in `libs.versions.toml` changes (check whether
`core/apple/src/files/FileSystemApple.kt` uses `dirname_r`, and drop the iOS
`createParentDirectories` if so), or a PR adds `Path.parent`, `Path.name` or
`createDirectories` to `sdk/core/src/commonMain` or `sdk/core/src/iosMain`.

## How it was paid

> Filled when closing.
