---
id: ADR-0226
title: The advertised addresses follow the machine's, checked every five seconds
status: Accepted
date: 2026-11-26
superseded_by:
---

# ADR-0226 — The advertised addresses follow the machine's, checked every five seconds

## Context

The owner's phone stopped finding the desktop on the Wi-Fi (2026-11-26). The desktop had
been opened the day before, when its Wi-Fi interface held only a self-assigned
`169.254.200.69`. The next morning the Mac was on `10.0.0.124`, and `dns-sd -L` still
resolved the only instance to `appmonitor-169-254-200-69.local.`. The SDK's log on the phone
repeated *"cannot reach the desktop at 169.254.200.69:47474"*. The resolved TXT `id` was this
desktop's, so ADR-0168's pin was not involved. Reopening the desktop announced
`10.0.0.124`, and the phone connected (`lsof` showed the session).

The cause is in `MdnsAdvertiser.start`: it registers `LanAddresses.current()` once, and
nothing updates the list while the app runs. A laptop that sleeps, wakes or changes network
keeps announcing addresses it no longer has, and never announces the new ones. DEBT-0123
expected a link-local address to mislead a phone; this is how it happened.

Known constraints:

- One JmDNS instance per address (ADR-0174). Closing one takes 2 to 3 s.
- `LanAddresses` is the one filter for both the desktop and the fake client (ADR-0164).
- JmDNS has no callback for interface changes, and the JDK has none either.

## Options

1. **Check the addresses on a timer.** Every five seconds the server reads
   `LanAddresses.current()`. It registers each new address, and closes in the background each
   one that went away. Listing the interfaces costs a few milliseconds. An address that
   failed to register is not tried again until it goes away and comes back, so a broken
   interface does not cost a JmDNS start every five seconds.
2. **Restart the whole advertiser on a change.** Simpler code, but a few seconds without any
   instance on every change, including for addresses that did not change.
3. **Watch the OS's network change notices** (SystemConfiguration on macOS, netlink on Linux,
   `NotifyAddrChange` on Windows). Immediate, but native code on three systems for a delay
   that a five-second timer already makes short.

## Decision

Option 1. `MdnsAdvertiser.refresh()` does one round. `AppMonitorServer` runs it every
`ADDRESS_CHECK_PERIOD` (5 s) from a successful `start()` until `stop()`, which cancels the
loop before releasing mDNS. A link-local address is still announced while an interface
holds one: that is how a phone on the USB link finds the desktop (DEBT-0123).

## Consequences

- After sleep, wake or a network change, a device finds the desktop within about five
  seconds of the new address, without a restart.
- The status line's mDNS problem is still read once, at start, so it can stay stale after a
  change (DEBT-0138).
- A device that resolved a stale instance shortly before the change retries it until its
  backoff runs discovery again (SDD 04).
