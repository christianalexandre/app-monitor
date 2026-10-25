#if APPMONITOR_DEBUG
#include "AppMonitorConnections.h"
#include "rebind.h"

#include <dlfcn.h>
#include <errno.h>
#include <mach-o/dyld.h>
#include <mach-o/loader.h>
#include <stdatomic.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

// Sockets numbered from here on are never counted.
#define MAX_FDS 4096

static _Atomic bool counted[MAX_FDS];
static _Atomic int64_t sent[MAX_FDS];
static _Atomic int64_t received[MAX_FDS];

static appmonitor_connected_fn on_connected;
static appmonitor_closing_fn on_closing;
static appmonitor_resolved_fn on_resolved;

static int (*real_connect)(int, const struct sockaddr *, socklen_t);
static int (*real_close)(int);
static int (*real_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
static ssize_t (*real_read)(int, void *, size_t);
static ssize_t (*real_readv)(int, const struct iovec *, int);
static ssize_t (*real_recv)(int, void *, size_t, int);
static ssize_t (*real_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
static ssize_t (*real_recvmsg)(int, struct msghdr *, int);
static ssize_t (*real_write)(int, const void *, size_t);
static ssize_t (*real_writev)(int, const struct iovec *, int);
static ssize_t (*real_send)(int, const void *, size_t, int);
static ssize_t (*real_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
static ssize_t (*real_sendmsg)(int, const struct msghdr *, int);

static inline bool is_counted(int fd) {
  return fd >= 0 && fd < MAX_FDS && atomic_load_explicit(&counted[fd], memory_order_relaxed);
}

static inline ssize_t count(_Atomic int64_t *totals, int fd, ssize_t n) {
  if (n > 0 && is_counted(fd)) atomic_fetch_add_explicit(&totals[fd], n, memory_order_relaxed);
  return n;
}

static int64_t now_ms(void) {
  struct timespec now;
  clock_gettime(CLOCK_REALTIME, &now);
  return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static bool is_stream(int fd) {
  int type = 0;
  socklen_t length = sizeof type;
  return getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &length) == 0 && type == SOCK_STREAM;
}

static int wrapped_connect(int fd, const struct sockaddr *address, socklen_t length) {
  int64_t started = now_ms();
  int result = real_connect(fd, address, length);
  int error = result == 0 ? 0 : errno;
  // A connect again on a counted socket (EALREADY, EISCONN) is the same connection.
  if (fd >= 0 && fd < MAX_FDS && address != NULL && !is_counted(fd) &&
      (address->sa_family == AF_INET || address->sa_family == AF_INET6) && is_stream(fd)) {
    atomic_store(&sent[fd], 0);
    atomic_store(&received[fd], 0);
    atomic_store(&counted[fd], on_connected(fd, address, length, started, error));
  }
  if (result != 0) errno = error;
  return result;
}

static int wrapped_close(int fd) {
  if (fd >= 0 && fd < MAX_FDS && atomic_exchange(&counted[fd], false)) {
    int saved = errno;
    int64_t out = atomic_load(&sent[fd]);
    int64_t in = atomic_load(&received[fd]);
    int error = 0;
    socklen_t length = sizeof error;
    getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length);
    struct sockaddr_storage peer;
    socklen_t peer_length = sizeof peer;
    if (error == 0 && out == 0 && in == 0 && getpeername(fd, (struct sockaddr *)&peer, &peer_length) != 0) {
      error = errno;
    }
    on_closing(fd, out, in, error);
    errno = saved;
  }
  return real_close(fd);
}

static int wrapped_getaddrinfo(
    const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **result) {
  int status = real_getaddrinfo(node, service, hints, result);
  if (status == 0 && node != NULL && result != NULL) {
    int saved = errno;
    on_resolved(node, *result);
    errno = saved;
  }
  return status;
}

static ssize_t wrapped_read(int fd, void *buffer, size_t size) {
  return count(received, fd, real_read(fd, buffer, size));
}

static ssize_t wrapped_readv(int fd, const struct iovec *vector, int n) {
  return count(received, fd, real_readv(fd, vector, n));
}

static ssize_t wrapped_recv(int fd, void *buffer, size_t size, int flags) {
  return count(received, fd, real_recv(fd, buffer, size, flags));
}

static ssize_t wrapped_recvfrom(
    int fd, void *buffer, size_t size, int flags, struct sockaddr *address, socklen_t *length) {
  return count(received, fd, real_recvfrom(fd, buffer, size, flags, address, length));
}

static ssize_t wrapped_recvmsg(int fd, struct msghdr *message, int flags) {
  return count(received, fd, real_recvmsg(fd, message, flags));
}

static ssize_t wrapped_write(int fd, const void *buffer, size_t size) {
  return count(sent, fd, real_write(fd, buffer, size));
}

static ssize_t wrapped_writev(int fd, const struct iovec *vector, int n) {
  return count(sent, fd, real_writev(fd, vector, n));
}

static ssize_t wrapped_send(int fd, const void *buffer, size_t size, int flags) {
  return count(sent, fd, real_send(fd, buffer, size, flags));
}

static ssize_t wrapped_sendto(
    int fd, const void *buffer, size_t size, int flags, const struct sockaddr *address, socklen_t length) {
  return count(sent, fd, real_sendto(fd, buffer, size, flags, address, length));
}

static ssize_t wrapped_sendmsg(int fd, const struct msghdr *message, int flags) {
  return count(sent, fd, real_sendmsg(fd, message, flags));
}

static struct rebinding rebindings[] = {
    {"connect", wrapped_connect, NULL},     {"close", wrapped_close, NULL},
    {"getaddrinfo", wrapped_getaddrinfo, NULL},
    {"read", wrapped_read, NULL},           {"readv", wrapped_readv, NULL},
    {"recv", wrapped_recv, NULL},           {"recvfrom", wrapped_recvfrom, NULL},
    {"recvmsg", wrapped_recvmsg, NULL},     {"write", wrapped_write, NULL},
    {"writev", wrapped_writev, NULL},       {"send", wrapped_send, NULL},
    {"sendto", wrapped_sendto, NULL},       {"sendmsg", wrapped_sendmsg, NULL},
};

// Shared cache images call each other directly, and an arm64e image's pointers are signed.
static void rebind_image(const struct mach_header *header, intptr_t slide) {
  if (header->flags & MH_DYLIB_IN_CACHE) return;
  if (header->cputype == CPU_TYPE_ARM64 && (header->cpusubtype & ~CPU_SUBTYPE_MASK) == CPU_SUBTYPE_ARM64E) return;
  rebind_symbols_image((void *)header, slide, rebindings, sizeof rebindings / sizeof rebindings[0]);
}

static bool resolve(void **real, const char *name) {
  *real = dlsym(RTLD_DEFAULT, name);
  return *real != NULL;
}

bool appmonitor_connections_install(
    appmonitor_connected_fn connected, appmonitor_closing_fn closing, appmonitor_resolved_fn resolved) {
  static atomic_flag done = ATOMIC_FLAG_INIT;
  if (atomic_flag_test_and_set(&done)) return false;
  on_connected = connected;
  on_closing = closing;
  on_resolved = resolved;
  bool found = resolve((void **)&real_connect, "connect") && resolve((void **)&real_close, "close") &&
               resolve((void **)&real_getaddrinfo, "getaddrinfo") && resolve((void **)&real_read, "read") &&
               resolve((void **)&real_readv, "readv") && resolve((void **)&real_recv, "recv") &&
               resolve((void **)&real_recvfrom, "recvfrom") && resolve((void **)&real_recvmsg, "recvmsg") &&
               resolve((void **)&real_write, "write") && resolve((void **)&real_writev, "writev") &&
               resolve((void **)&real_send, "send") && resolve((void **)&real_sendto, "sendto") &&
               resolve((void **)&real_sendmsg, "sendmsg");
  if (!found) return false;
  _dyld_register_func_for_add_image(rebind_image);
  return true;
}
#endif
