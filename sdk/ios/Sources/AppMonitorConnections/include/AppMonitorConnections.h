#ifndef APPMONITOR_CONNECTIONS_H
#define APPMONITOR_CONNECTIONS_H

#include <netdb.h>
#include <stdbool.h>
#include <stdint.h>
#include <sys/socket.h>

// Called after an app's `connect` on an IPv4 or IPv6 stream socket returns; `error` is its errno, 0 on
// success. Returns true to count the socket's bytes until its `close`.
typedef bool (*appmonitor_connected_fn)(
    int fd, const struct sockaddr *address, socklen_t length, int64_t started_ms, int error);

// Called before a counted socket's `close`; `error` is a connect that never completed, else 0.
typedef void (*appmonitor_closing_fn)(int fd, int64_t sent, int64_t received, int error);

// Called after a successful `getaddrinfo` of `node`.
typedef void (*appmonitor_resolved_fn)(const char *node, const struct addrinfo *result);

// Rebinds the socket calls of every image not in the shared cache and not arm64e, now and as images
// load (ADR-0100). Once per process: false when called again.
bool appmonitor_connections_install(
    appmonitor_connected_fn connected, appmonitor_closing_fn closing, appmonitor_resolved_fn resolved);

#endif
