---
id: DEBT-0026
title: The ABI dump can miss a public no-arg secondary constructor
status: Open
date: 2026-10-22
trigger: The Kotlin version in `gradle/libs.versions.toml` changes, or a public SDK class gets a non-public primary constructor
paid_date:
---

# DEBT-0026 — The ABI dump can miss a public no-arg secondary constructor

## What is missing

With Kotlin 2.4.21, a class with a non-public primary constructor and a
public `constructor()` gets a dump without `<init> ()V` (ADR-0026). The
no-op API test compares dumps, so such a constructor missing from either
side goes unnoticed. Symptom: an app compiles in debug and fails in release
on `AppMonitorInterceptor()` (or a future class of the same shape).

## Why it was accepted

The only class with that shape, `AppMonitorInterceptor`, now declares both
constructors as secondary and is dumped correctly. Reporting the dumper
behaviour upstream, or a reflection check, costs more than the one class
it covers.

## Trigger

The Kotlin version changes (re-check whether the dumper lists the
constructor; if it does, close this), or a public SDK class gets a
non-public primary constructor beside a public no-arg one.

## How it was paid

> Filled when closing.
