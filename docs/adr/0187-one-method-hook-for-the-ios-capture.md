---
id: ADR-0187
title: One method hook for the iOS capture
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0187 — One method hook for the iOS capture

## Context

The Swift SDK replaces Objective-C methods (ADR-0043, ADR-0045, ADR-0046, ADR-0117). Each hook
repeated a `typealias Original`, an `unsafeBitCast`, a block and `method_setImplementation`, and a
wrong arity crashed at the first call (DEBT-0103). A `@convention(c)` type cannot take a parameter
pack (the compiler rejects it), so arities cannot be folded into one signature. A generic parameter can
stand for the whole `@convention(c)` type, and `hookUpload` already passed a block through `Any`.

## Options

1. **One helper per arity, background replies included**, as DEBT-0103 proposed. The background
   hooks take a `UInt` and differ in count, and would need helpers with one caller each.
2. **A generic `replace` over the C signature, `before` for object-only methods.** Each hook keeps
   its signature beside its body, so a table does not hide which method it relies on.
3. **A table of selectors and arities.** DEBT-0103 already rejected it for that reason.

## Decision

Option 2: `MethodHook.replace` and three `before` overloads, in `MethodHook.swift`, with the
reentrancy option on the one-argument `before`, the only hook that needs it. `replace` compares
the method's argument count to the one stated, and logs and leaves the method alone when they differ.

## Consequences

A wrong count no longer crashes the app. A wrong argument type within the right count still does:
the runtime's type encodings for private methods were not checked. The hooks' behaviour is unchanged.
