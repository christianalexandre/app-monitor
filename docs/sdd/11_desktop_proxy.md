# 11 — Desktop proxy

The proxy mode of ADR-0004 layer 4 and ADR-0101: an HTTP proxy on
`127.0.0.1`, off by default, that relays plain HTTP and `CONNECT`
tunnels, ends their TLS with a leaf from its own CA, and stores each
exchange as `CAPTURE_LAYER_PROXY`. Code in `desktop/proxy` (Netty and
Bouncy Castle, ADR-0105); the switch, status and commands in
`desktop/app` (`ProxyController`, the settings).

## How it works

### Exposure and feed

- Binds `127.0.0.1:47476` only (ADR-0101 A.1); `APPMONITOR_PROXY_PORT`
  moves it (`proxyPort()`). Nothing else on the LAN reaches it, so it
  has no approval step.
- A simulator shares the Mac's network, so an app launched with
  `SIMCTL_CHILD_grpc_proxy=http://127.0.0.1:<port> xcrun simctl launch
  booted <bundle id>` sends its gRPC-Core traffic through it (ADR-0101
  B.1); from Xcode, the same variable without the prefix in the scheme.
  `URLSession` ignores it and stays on layer 1. The settings show this
  command and a `curl --proxy … --cacert <ca file>` one.

### The CA

`ProxyCa.loadOrCreate(dataDirectory())` on each start (ADR-0101 C.1):

- `proxy-ca.pem` (the certificate) and `proxy-ca-key.pem` (PKCS#8)
  beside `appmonitor.db`, each created `0600` before its content is
  written (SDD 09's file protection).
- Missing, unreadable, or ending within a day → a new one: EC P-256,
  basic constraints CA, key usage `keyCertSign` and `cRLSign`, valid
  365 days, subject `App Monitor proxy CA <date>`.
- A leaf per host, made on first use and kept in memory until the CA is
  dropped: EC P-256, the host as DNS or IP SAN, `serverAuth`, 30 days
  and never past the CA.
- "Reset proxy CA" and "Clear all data" delete both files; a running
  proxy restarts with a new CA, and the settings remind to reset the
  simulators' keychains. Installing it on simulators is M8.6.

### One client connection

`ClientHandler`, one per accepted connection, on Netty's event loop:

1. **`CONNECT host:port`** → `200 Connection Established`, the HTTP codec
   comes off, and `OptionalSslHandler` looks at the first bytes: TLS →
   the handshake with the leaf for `host`, plain → HTTP as is. A failed
   handshake (the client refused the leaf: pinning, or the CA not
   trusted) is stored as a failed `CONNECT https://host:port` exchange,
   error `TLS handshake with the client failed: …`, and the connection
   closes (ADR-0101 E.1).
2. **A request** inside a tunnel goes to the tunnel's host; outside one
   it needs an absolute `http://` or `https://` URL, else `400` and
   nothing is stored. The URI becomes origin-form, `Proxy-Connection`
   goes, `Host` is set when missing.
3. **Upstream**: one connection at a time, on the same event loop,
   reused while requests keep the same target. `https` targets are
   verified against the JVM's trust store with hostname checks.
4. **Relay**: request and response parts are forwarded as they arrive,
   not aggregated; each body keeps at most `Protocol.MAX_BODY_BYTES` for
   the store, with its full size. After a request's last part the client
   is not read again until its response ends, so a pipelined request
   waits in a queue.
5. **Failures**: connect refused or timed out (10 s), the origin's TLS
   refused, a write failing, or the origin closing mid-response → the
   exchange is stored failed with the reason; the client gets `502`
   with it as text when no response started, else its connection closes.
   The client closing first stores `The client closed the connection`.
6. **`101`**: the exchange is stored with status 101, both codecs come
   off and bytes are relayed as they are (DEBT-0077).
7. A `1xx` other than 101 is forwarded and not stored. A response that
   is not keep-alive, or a request that was not, closes the client after
   the last part.

### What is stored

Each start of the proxy is one session (`ProxyRecording`): `Hello` with
`device_name` `Proxy`, no device or app id, platform unspecified,
`sdk_name` `proxy`, peer `127.0.0.1`. Each exchange sends the events an
SDK sends (ADR-0105 C.1): `RequestStarted` once the request is in full,
or when it fails first, with the time the request began; then
`ResponseReceived` or `RequestFailed`. Headers are stored as the client
and the origin sent them. Events wait in an unbounded channel between the
event loop and the store's queue, so the relay never blocks on the
store. Stopping the proxy writes what is left, then the session's end.

### Lifecycle

`ProxyServer` is single use; `ProxyController` builds one per start.

| Call | Not started | Running | Stopped |
| --- | --- | --- | --- |
| `start()` | binds, returns the port; a taken port throws `BindException` and frees the event loops (`ProxyServerTest`: taken port) | throws `IllegalStateException` (`ProxyLifecycleTest`: start twice) | throws `IllegalStateException` (`ProxyLifecycleTest`: start after stop) |
| `stop()` | nothing (`ProxyLifecycleTest`: stop before start) | closes the listener, then every client and upstream connection, waits up to 2 s for the event loops; the port is free after (`ProxyLifecycleTest`: open connections, frees the port) | nothing (`ProxyLifecycleTest`: stop twice) |
| a client connects | refused | served (`ProxyServerTest`) | refused |

`ProxyController`, the settings' switch:

| Call | Off | Running | Port taken / failed | Closed |
| --- | --- | --- | --- | --- |
| `start()` (launch) | reads `proxy.enabled` (missing = off) and starts when on | nothing | nothing | nothing |
| `setEnabled(true)` | stores it, loads or makes the CA, starts; `PortTaken` or `Failed` as a status (`ProxyWiringTest`) | nothing | tries again | stays off (`ProxyWiringTest`) |
| `setEnabled(false)` | stores it | stops, ends the session (`ProxyWiringTest`) | `Off` | stays off |
| `resetCa()` | deletes the CA | stops, deletes, starts with a new CA and session (`ProxyWiringTest`) | deletes | deletes |
| `afterClearAll()` | as `resetCa()`, the switch written back when on (`ProxyWiringTest`) | same | same | same |
| `close()` | `Closed` | stops, then `Closed` | `Closed` | nothing |

The app closes the MCP server, then the proxy, then the device server,
then the store (`stopAll`, `stopBoth`); the stop hook's budget counts
the proxy's 2 s.

## Limits

- Loopback only: physical devices cannot use it (ADR-0101 A.1).
- HTTP/1.1 only, no ALPN; HTTP/2 and gRPC decoding are M8.6.
- Nothing after a `101` is captured (DEBT-0077); no pass-through list for
  pinned hosts (DEBT-0078).
- One request at a time per client connection.
- Bodies are stored as sent: a compressed body keeps its
  `Content-Encoding`.
