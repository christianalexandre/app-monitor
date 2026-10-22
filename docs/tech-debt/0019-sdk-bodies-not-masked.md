---
id: DEBT-0019
title: The SDK masks headers and query parameters, never bodies
status: Open
date: 2026-10-22
trigger: The first request to mask a field inside a request or response body
paid_date:
---

# DEBT-0019 — The SDK masks headers and query parameters, never bodies

## What is missing

ADR-0018 masks header values and opted-in query parameters on the device.
Bodies are sent as captured, up to `maxBodyBytes`: a password in a login
form or a token in a JSON response reaches the desktop in clear.

## Why it was accepted

It is a debug tool on the developer's own LAN, and body masking needs a
rule per format (JSON field names, form fields) shared by every SDK. No app
has asked for it yet.

## Trigger

The first request to mask a field inside a request or response body.

## How it was paid

> Filled when closing.
