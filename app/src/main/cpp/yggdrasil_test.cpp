/*
 * yggdrasil_test - Unified Yggdrasil KPM self-test (Android arm64, zero args).
 *
 * One binary exercising every module feature in order:
 *   core      availability + version ABI
 *   memfd     PR_YGGDRASIL_MEMFD_CREATE (custom names, flags, validation)
 *   merge     PR_YGGDRASIL_VMA_MERGE_VIEW (merge/clear/validate/fork)
 *   maps      PR_YGGDRASIL_MAPS_POLICY (rules, scopes, map_files, fork, merge interplay)
 *   dbg       PR_YGGDRASIL_HIDE_DBG (status rewrite for stopped/traced tasks)
 *   ptrace    PR_YGGDRASIL_PTRACE_SPOOF (fake NT_ARM_HW_* regsets)
 *   label     PR_YGGDRASIL_LABEL_SPOOF (control plane + query mechanics)
 *   hwbp      attach/replace/detach (trampoline) + observe/unobserve/events
 *   syscall   PR_YGGDRASIL_SYSCALL_CONFIG/READ_EVENT (rewrite, capture, backtrace, uid scope)
 *
 * All scenarios are built in: fixture files are created under /data/local/tmp,
 * target processes are forked children coordinated over pipes, and every
 * expectation is asserted by reading /proc directly - no shell scripts, no
 * parameters. Run as root on device:  su -c /data/local/tmp/yggdrasil_test
 *
 * Output: one line per check; final summary; exit code 0 iff nothing failed.
 * An optional argv[1] substring filters which feature sections run.
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <linux/openat2.h>
#include <linux/stat.h>

#include "yggdrasil.h"
/* Shared with the userspace helper: the same capture schema table and the
 * same renderer the CLI ships, so these checks cover production code. */
#include "yggdrasil_syscall_format.h"

#ifndef SOL_SOCKET
#define SOL_SOCKET 1
#endif
#ifndef SO_PEERSEC
#define SO_PEERSEC 31
#endif
#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif
#ifndef PTRACE_SETREGSET
#define PTRACE_SETREGSET 0x4205
#endif
#ifndef NT_ARM_HW_BREAK
#define NT_ARM_HW_BREAK 0x402
#endif
#ifndef NT_ARM_HW_WATCH
#define NT_ARM_HW_WATCH 0x403
#endif
#ifndef PTRACE_SEIZE
#define PTRACE_SEIZE 0x4206
#endif
#ifndef PTRACE_INTERRUPT
#define PTRACE_INTERRUPT 0x4207
#endif
#ifndef PTRACE_ATTACH
#define PTRACE_ATTACH 16
#endif
#ifndef PTRACE_DETACH
#define PTRACE_DETACH 17
#endif
#ifndef RESOLVE_NO_SYMLINKS
#define RESOLVE_NO_SYMLINKS 0x4
#endif
#ifndef __NR_memfd_create
#define __NR_memfd_create 279 /* arm64 */
#endif

#define TEST_TMP_BASE "/data/local/tmp/ygg_test_"

/* ------------------------------------------------------------------ */
/* Tiny test framework                                                */
/* ------------------------------------------------------------------ */

static int g_checks;
static int g_failed;
static const char *g_section = "";

static void check_begin(void)
{
    g_checks++;
}

static void check_pass(const char *name)
{
    printf("[ ok ] %s: %s\n", g_section, name);
}

static void check_fail(const char *name, const char *fmt, ...)
{
    char detail[256];
    va_list ap;

    g_failed++;
    va_start(ap, fmt);
    vsnprintf(detail, sizeof(detail), fmt, ap);
    va_end(ap);
    printf("[FAIL] %s: %s (%s)\n", g_section, name, detail);
}

/* Zero errno first: pure string-compare failures never set it, so without
 * this the macro would print whatever error a long-gone syscall left behind
 * (e.g. ENAMETOOLONG from the memfd section) and mislead the reader. */
#define CHECK(cond, name)                                    \
    do {                                                     \
        int ok_;                                             \
        int err_;                                            \
        errno = 0;                                           \
        ok_ = (cond);                                        \
        err_ = errno;                                        \
        check_begin();                                       \
        if (ok_)                                             \
            check_pass(name);                                \
        else                                                 \
            check_fail(name, "line %d: errno %d(%s)",        \
                       __LINE__, err_, strerror(err_));      \
    } while (0)

#define CHECK_EQ(a, b, name)                                          \
    do {                                                              \
        long long va_;                                                \
        long long vb_;                                                \
        int err_;                                                     \
        errno = 0; /* stale errno would print as real */              \
        va_ = (long long)(a);                                         \
        err_ = errno; /* captured before (b) can clobber it */        \
        vb_ = (long long)(b);                                         \
        check_begin();                                                \
        if (va_ == vb_)                                               \
            check_pass(name);                                         \
        else                                                          \
            check_fail(name, "line %d: got %lld want %lld errno "     \
                       "%d(%s)", __LINE__, va_, vb_, err_,            \
                       strerror(err_));                               \
    } while (0)

#define CHECK_STR_EQ(a, b, name)                                      \
    do {                                                              \
        const char *va_ = (a);                                        \
        const char *vb_ = (b);                                        \
        check_begin();                                                \
        if (va_ && vb_ && !strcmp(va_, vb_))                          \
            check_pass(name);                                         \
        else                                                          \
            check_fail(name, "line %d: got \"%s\" want \"%s\"",       \
                       __LINE__, va_ ? va_ : "(null)",                \
                       vb_ ? vb_ : "(null)");                         \
    } while (0)

/* Evaluate a prctl and require success. */
#define PRCTL_OK(name, option, ...)                                   \
    do {                                                              \
        long r_ = prctl(option, ##__VA_ARGS__);                       \
        check_begin();                                                \
        if (r_ >= 0)                                                  \
            check_pass(name);                                         \
        else                                                          \
            check_fail(name, "line %d: prctl %s -> %s", __LINE__,     \
                       name, strerror(errno));                        \
    } while (0)

/* Evaluate a prctl and require a specific errno. */
#define PRCTL_ERR(name, want_errno, option, ...)                      \
    do {                                                              \
        long r_ = prctl(option, ##__VA_ARGS__);                       \
        check_begin();                                                \
        if (r_ < 0 && errno == (want_errno))                          \
            check_pass(name);                                         \
        else                                                          \
            check_fail(name, "line %d: want errno %s got ret %ld "    \
                       "errno %d(%s)", __LINE__, #want_errno,         \
                       r_, errno, strerror(errno));                   \
    } while (0)

/* ------------------------------------------------------------------ */
/* Small helpers                                                      */
/* ------------------------------------------------------------------ */

static long yprctl(int option, unsigned long a2, unsigned long a3,
                   unsigned long a4, unsigned long a5)
{
    return prctl(option, a2, a3, a4, a5);
}


static int write_fixture(const char *path, const char *data, size_t len)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);

    if (fd < 0)
        return -1;
    if (write(fd, data, len) != (ssize_t)len) {
        close(fd);
        return -1;
    }
    close(fd);
    return 0;
}

/* Read a whole (small) file; returns length or -1. NUL-terminates.
 * Procfs is free to hand a file out in several short reads, so drain until
 * EOF instead of trusting one read() to return everything that fits. */
static int read_file(const char *path, char *buf, size_t size)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    size_t filled = 0;
    ssize_t n = 0;

    if (fd < 0)
        return -1;
    while (filled < size - 1) {
        n = read(fd, buf + filled, size - 1 - filled);
        if (n <= 0)
            break;
        filled += (size_t)n;
    }
    close(fd);
    if (n < 0)
        return -1;
    buf[filled] = '\0';
    return (int)filled;
}

static void fmt_proc_path(char *out, size_t size, pid_t pid,
                          const char *leaf)
{
    if (pid > 0)
        snprintf(out, size, "/proc/%d/%s", pid, leaf);
    else
        snprintf(out, size, "/proc/self/%s", leaf);
}

/* Count lines of /proc/<pid>/<leaf> containing `needle`. */
static int proc_count_lines(pid_t pid, const char *leaf, const char *needle)
{
    char path[64];
    char buf[65536];
    int len;
    int count = 0;
    char *line;

    fmt_proc_path(path, sizeof(path), pid, leaf);
    len = read_file(path, buf, sizeof(buf));
    if (len <= 0)
        return -1;

    line = strtok(buf, "\n");
    while (line) {
        if (strstr(line, needle))
            count++;
        line = strtok(NULL, "\n");
    }
    return count;
}

/* Count all lines of /proc/<pid>/<leaf>. */

/*
 * Find the first maps line containing `needle` and parse its
 * "<start>-<end> <perms>" header. Returns 0 on success.
 *
 * Callers pass NULL for outputs they don't need; the sscanf conversion
 * targets are always real stack objects because %lx into a NULL pointer is
 * undefined behavior (glibc segfaults on it, bionic happens not to).
 */
static int maps_find(pid_t pid, const char *needle, unsigned long *start,
                     unsigned long *end, char perms[5])
{
    char path[64];
    char buf[65536];
    int len;
    char *line;
    unsigned long start_dummy;
    unsigned long end_dummy;

    if (!start)
        start = &start_dummy;
    if (!end)
        end = &end_dummy;

    fmt_proc_path(path, sizeof(path), pid, "maps");
    len = read_file(path, buf, sizeof(buf));
    if (len <= 0)
        return -1;

    line = strtok(buf, "\n");
    while (line) {
        if (strstr(line, needle)) {
            char perms_buf[5] = {};

            if (sscanf(line, "%lx-%lx %4s", start, end, perms_buf) == 3) {
                if (perms)
                    memcpy(perms, perms_buf, 5);
                return 0;
            }
        }
        line = strtok(NULL, "\n");
    }
    return -1;
}

/* Look up one "Key:\tValue" field of /proc/<pid>/status into out. */
static int status_field(pid_t pid, const char *key, char *out, size_t size)
{
    char path[64];
    char buf[16384];
    int len;
    char *line;
    size_t keylen = strlen(key);

    fmt_proc_path(path, sizeof(path), pid, "status");
    len = read_file(path, buf, sizeof(buf));
    if (len <= 0)
        return -1;

    line = strtok(buf, "\n");
    while (line) {
        if (!strncmp(line, key, keylen) && line[keylen] == ':') {
            const char *v = line + keylen + 1;

            while (*v == '\t' || *v == ' ')
                v++;
            snprintf(out, size, "%s", v);
            return 0;
        }
        line = strtok(NULL, "\n");
    }
    return -1;
}

/* Readlink into buf; returns length or -1 (errno preserved by caller). */
static int readlink_path(const char *path, char *buf, size_t size)
{
    ssize_t n = readlink(path, buf, size - 1);

    if (n < 0)
        return -1;
    buf[n] = '\0';
    return (int)n;
}

/* Wait until predicate() is nonzero, up to timeout_ms. */

/* ------------------------------------------------------------------ */
/* Child-process plumbing                                             */
/* ------------------------------------------------------------------ */

#define MAX_CHILDREN 32
static pid_t g_children[MAX_CHILDREN];
static int g_child_count;

static void track_child(pid_t pid)
{
    if (g_child_count < MAX_CHILDREN)
        g_children[g_child_count++] = pid;
}

/* Kill and reap every tracked child (best effort cleanup). */
static void reap_children(void)
{
    int i;

    for (i = 0; i < g_child_count; i++) {
        kill(g_children[i], SIGKILL);
        waitpid(g_children[i], NULL, 0);
    }
    g_child_count = 0;
}

/* Read one byte from fd (blocking). */
static int pipe_getc(int fd)
{
    unsigned char c;
    ssize_t n = read(fd, &c, 1);

    if (n != 1)
        return -1;
    return c;
}

static int pipe_putc(int fd, unsigned char c)
{
    ssize_t n = write(fd, &c, 1);

    return n == 1 ? 0 : -1;
}

/*
 * Read a little-endian u64 from a pipe. Uses readv for symmetry with
 * pipe_write_u64: a nr-63 rewrite rule would corrupt plain reads.
 */
static int pipe_read_u64(int fd, uint64_t *out)
{
    unsigned char b[8];
    struct iovec iov;
    ssize_t n;
    uint64_t v = 0;
    int i;

    iov.iov_base = b;
    iov.iov_len = sizeof(b);
    n = syscall(SYS_readv, fd, &iov, 1);
    if (n != 8)
        return -1;
    for (i = 7; i >= 0; i--)
        v = (v << 8) | b[i];
    *out = v;
    return 0;
}

/*
 * Write a little-endian u64 to a pipe. Uses writev, not write: an nr-64
 * rewrite rule would truncate every plain write the traced child makes,
 * including its own answers.
 */
static int pipe_write_u64(int fd, uint64_t v)
{
    unsigned char b[8];
    struct iovec iov;
    int i;

    for (i = 0; i < 8; i++) {
        b[i] = (unsigned char)(v & 0xff);
        v >>= 8;
    }
    iov.iov_base = b;
    iov.iov_len = sizeof(b);
    return syscall(SYS_writev, fd, &iov, 1) == 8 ? 0 : -1;
}

/* ------------------------------------------------------------------ */
/* maps-policy request helpers (mirror the client's builders)         */
/* ------------------------------------------------------------------ */

static void maps_init_request(struct ygg_maps_request *req, uint32_t op,
                              int has_pid, uint32_t pid)
{
    memset(req, 0, sizeof(*req));
    req->size = sizeof(*req);
    req->version = YGG_MAPS_ABI_VERSION;
    req->op = op;
    if (has_pid) {
        req->flags = YGG_MAPS_F_TARGET_PID;
        req->pid = pid;
    }
}

static long maps_send(struct ygg_maps_request *req)
{
    errno = 0;
    return prctl(PR_YGGDRASIL_MAPS_POLICY, req, 0, 0, 0);
}

static long maps_add_rule_global(uint32_t action, uint32_t match_kind,
                                 const char *pattern)
{
    struct ygg_maps_request req;

    maps_init_request(&req, YGG_MAPS_OP_ADD_RULE, 0, 0);
    req.rule.action = action;
    req.rule.match_kind = match_kind;
    if (pattern)
        snprintf(req.rule.pattern, sizeof(req.rule.pattern), "%s", pattern);
    if (action == YGG_MAPS_ACTION_REWRITE_PERMS)
        memcpy(req.rule.set_perms, "---", 4);
    return maps_send(&req);
}

static long maps_add_rule_full(uint32_t op, int has_pid, uint32_t pid,
                               const struct ygg_maps_rule *rule)
{
    struct ygg_maps_request req;

    maps_init_request(&req, op, has_pid, pid);
    if (rule)
        req.rule = *rule;
    return maps_send(&req);
}

static long maps_clear(int has_pid, uint32_t pid)
{
    struct ygg_maps_request req;

    maps_init_request(&req, YGG_MAPS_OP_CLEAR, has_pid, pid);
    return maps_send(&req);
}

static long maps_clear_all(void)
{
    struct ygg_maps_request req;

    maps_init_request(&req, YGG_MAPS_OP_CLEAR, 0, 0);
    req.flags |= YGG_MAPS_F_ALL_SCOPES;
    return maps_send(&req);
}

/* GET a scope; returns 0 and fills total/copied on success. */
static int maps_get(int has_pid, uint32_t pid, uint32_t *total,
                    uint32_t *copied, int *is_global,
                    struct ygg_maps_rule *rules_out, uint32_t rules_max)
{
    static uint8_t buf[sizeof(struct ygg_maps_dump) +
                       YGG_MAPS_MAX_RULES * sizeof(struct ygg_maps_rule)];
    struct ygg_maps_request req;
    const struct ygg_maps_dump *dump;
    long ret;

    maps_init_request(&req, YGG_MAPS_OP_GET, has_pid, pid);
    req.buffer = (uint64_t)(uintptr_t)buf;
    req.buffer_size = sizeof(buf);

    errno = 0;
    ret = prctl(PR_YGGDRASIL_MAPS_POLICY, &req, 0, 0, 0);
    if (ret < 0)
        return -1;

    dump = (const struct ygg_maps_dump *)buf;
    if (total)
        *total = dump->total_rules;
    if (copied)
        *copied = dump->copied_rules;
    if (is_global)
        *is_global = dump->scope_global;
    if (rules_out && copied) {
        uint32_t n = dump->copied_rules;

        if (n > rules_max)
            n = rules_max;
        memcpy(rules_out, buf + sizeof(*dump), n * sizeof(*rules_out));
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* Section: core                                                      */
/* ------------------------------------------------------------------ */

static void test_core(void)
{
    g_section = "core";

    PRCTL_OK("available", PR_YGGDRASIL_AVAILABLE, 0, 0, 0, 0);
    CHECK_EQ(prctl(PR_YGGDRASIL_AVAILABLE, 0, 0, 0, 0), 1,
             "available returns 1");
    CHECK_EQ(prctl(PR_YGGDRASIL_GET_VERSION, 0, 0, 0, 0),
             (long)YGG_VERSION_CODE, "get_version matches header");
}

/* ------------------------------------------------------------------ */
/* Section: memfd                                                     */
/* ------------------------------------------------------------------ */

static void test_memfd(void)
{
    static const char payload[] = "ygg-memfd-payload";
    char name[64];
    char link[256];
    char path[64];
    char buf[64];
    char longname[YGG_NAME_MAX + 2];
    int fd;
    int ctl;
    int cloexec;
    off_t off;
    ssize_t n;

    g_section = "memfd";
    snprintf(name, sizeof(name), "ygg_memfd_%d", (int)getpid());

    fd = prctl(PR_YGGDRASIL_MEMFD_CREATE, (unsigned long)name, 0, 0, 0);
    CHECK(fd >= 0, "create named fd");
    if (fd < 0)
        return;

    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    CHECK(readlink_path(path, link, sizeof(link)) >= 0, "fd has a link");
    CHECK(strstr(link, name) != NULL, "link carries custom name");
    /* A plain memfd fallback still contains the name under "memfd:", so the
     * prefix check is what actually proves the direct shmem path was used. */
    CHECK(strstr(link, "memfd:") == NULL,
          "link has no memfd: prefix (named path, not memfd_create fallback)");

    CHECK_EQ(write(fd, payload, sizeof(payload) - 1),
             (long)(sizeof(payload) - 1), "write to memfd");

    /*
     * lseek/read/write round-trip: same sequence as the kernel's own
     * memfd selftest. A plain lseek errno print plus a native
     * memfd_create control fd pin down whether a failure comes from the
     * direct shmem path or affects native memfds on this kernel too.
     */
    errno = 0;
    off = lseek(fd, 0, SEEK_SET);
    CHECK_EQ(off, 0, "lseek to start");
    memset(buf, 0, sizeof(buf));
    errno = 0;
    n = read(fd, buf, sizeof(buf));
    CHECK_EQ(n, (long)(sizeof(payload) - 1), "read back length");
    CHECK_STR_EQ(buf, payload, "read back content");
    close(fd);

    /* Control: native memfd on the same kernel must round-trip. */
    errno = 0;
    ctl = syscall(__NR_memfd_create, "ygg_ctl", 0);
    CHECK(ctl >= 0, "native memfd_create control fd");
    if (ctl >= 0) {
        CHECK_EQ(write(ctl, payload, sizeof(payload) - 1),
                 (long)(sizeof(payload) - 1), "native write");
        errno = 0;
        CHECK_EQ(lseek(ctl, 0, SEEK_SET), 0, "native lseek to start");
        errno = 0;
        CHECK_EQ(read(ctl, buf, sizeof(buf)),
                 (long)(sizeof(payload) - 1), "native read back");
        close(ctl);
    }

    fd = prctl(PR_YGGDRASIL_MEMFD_CREATE, (unsigned long)name,
               MFD_CLOEXEC, 0, 0);
    CHECK(fd >= 0, "create with MFD_CLOEXEC");
    if (fd >= 0) {
        cloexec = fcntl(fd, F_GETFD);
        CHECK((cloexec >= 0 && (cloexec & FD_CLOEXEC)), "FD_CLOEXEC set");
        close(fd);
    }

    /* Validation: only MFD_CLOEXEC is in the supported flag mask. */
    PRCTL_ERR("reject unsupported flags", EINVAL,
              PR_YGGDRASIL_MEMFD_CREATE, (unsigned long)name,
              MFD_ALLOW_SEALING, 0, 0);
    PRCTL_ERR("reject empty name", EINVAL,
              PR_YGGDRASIL_MEMFD_CREATE, (unsigned long)"", 0, 0, 0);
    memset(longname, 'a', sizeof(longname));
    longname[sizeof(longname) - 1] = '\0';
    PRCTL_ERR("reject overlong name", ENAMETOOLONG,
              PR_YGGDRASIL_MEMFD_CREATE, (unsigned long)longname, 0, 0, 0);

    /* A name of exactly YGG_NAME_MAX chars is accepted. */
    memset(longname, 'b', YGG_NAME_MAX);
    longname[YGG_NAME_MAX] = '\0';
    fd = prctl(PR_YGGDRASIL_MEMFD_CREATE, (unsigned long)longname, 0, 0, 0);
    CHECK(fd >= 0, "accept max-length name");
    if (fd >= 0)
        close(fd);
}

/* ------------------------------------------------------------------ */
/* Section: merge view                                                */
/* ------------------------------------------------------------------ */

struct merge_ctx {
    char left_path[128];
    char right_path[128];
    unsigned long first;
    unsigned long second;
    unsigned long end;
};

/* Reserve len bytes, unmap, then MAP_FIXED two file pages in it. */
static int map_adjacent_pair(const char *left, const char *right,
                             void **left_out, void **right_out)
{
    void *reserve;
    int lfd;
    int rfd;
    void *m0;
    void *m1;
    char page[4096];

    reserve = mmap(NULL, 8192, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (reserve == MAP_FAILED)
        return -1;
    munmap(reserve, 8192);

    memset(page, 'L', sizeof(page));
    lfd = open(left, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (lfd < 0 || write(lfd, page, sizeof(page)) != (ssize_t)sizeof(page)) {
        if (lfd >= 0)
            close(lfd);
        return -1;
    }
    memset(page, 'R', sizeof(page));
    rfd = open(right, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (rfd < 0 || write(rfd, page, sizeof(page)) != (ssize_t)sizeof(page)) {
        close(lfd);
        if (rfd >= 0)
            close(rfd);
        return -1;
    }

    m0 = mmap(reserve, 4096, PROT_READ, MAP_PRIVATE | MAP_FIXED, lfd, 0);
    m1 = mmap((char *)reserve + 4096, 4096, PROT_READ,
              MAP_PRIVATE | MAP_FIXED, rfd, 0);
    close(lfd);
    close(rfd);
    if (m0 == MAP_FAILED || m1 == MAP_FAILED)
        return -1;

    *left_out = m0;
    *right_out = m1;
    return 0;
}

static void test_merge_view(void)
{
    struct merge_ctx ctx;
    void *m0 = NULL;
    void *m1 = NULL;
    char merged_line_needle[160];
    int count;
    pid_t child;
    int ready_pipe[2];
    int done_pipe[2];
    uint64_t merged_lines = (uint64_t)-1;

    g_section = "merge";
    memset(&ctx, 0, sizeof(ctx));
    snprintf(ctx.left_path, sizeof(ctx.left_path), "%sleft_%d.bin",
             TEST_TMP_BASE, (int)getpid());
    snprintf(ctx.right_path, sizeof(ctx.right_path), "%sright_%d.bin",
             TEST_TMP_BASE, (int)getpid());

    CHECK(map_adjacent_pair(ctx.left_path, ctx.right_path, &m0, &m1) == 0,
          "map adjacent pair");
    if (!m0 || !m1)
        return;
    ctx.first = (unsigned long)m0;
    ctx.second = (unsigned long)m1;
    ctx.end = ctx.second + 4096;

    /* Baseline: two separate records. */
    CHECK(maps_find(0, ctx.left_path, NULL, NULL, NULL) == 0,
          "left record visible");
    CHECK(maps_find(0, ctx.right_path, NULL, NULL, NULL) == 0,
          "right record visible");

    snprintf(merged_line_needle, sizeof(merged_line_needle),
             "%lx-%lx ", ctx.first, ctx.end);

    /* Invalid ranges are rejected. */
    PRCTL_ERR("reject end<=start", EINVAL, PR_YGGDRASIL_VMA_MERGE_VIEW,
              ctx.first, ctx.first, 0, 0);
    PRCTL_ERR("reject unaligned start", EINVAL,
              PR_YGGDRASIL_VMA_MERGE_VIEW, ctx.first + 2048, ctx.end, 0, 0);
    PRCTL_ERR("reject single segment", EINVAL,
              PR_YGGDRASIL_VMA_MERGE_VIEW, ctx.first, ctx.second, 0, 0);

    /* A range with a gap in the middle is rejected. */
    munmap(m1, 4096);
    PRCTL_ERR("reject range with gap", EINVAL, PR_YGGDRASIL_VMA_MERGE_VIEW,
              ctx.first, ctx.end, 0, 0);
    {
        int fd = open(ctx.right_path, O_RDONLY | O_CLOEXEC);
        void *again;

        CHECK(fd >= 0, "reopen right file");
        again = mmap((void *)ctx.second, 4096, PROT_READ,
                     MAP_PRIVATE | MAP_FIXED, fd, 0);
        close(fd);
        CHECK(again == (void *)ctx.second, "restore right mapping");
    }

    /* Merge the pair: one record spanning [first, end) with left's path. */
    PRCTL_OK("install merge view", PR_YGGDRASIL_VMA_MERGE_VIEW,
             ctx.first, ctx.end, 0, 0);
    count = proc_count_lines(0, "maps", merged_line_needle);
    CHECK_EQ(count, 1, "maps shows merged record");
    count = proc_count_lines(0, "maps", ctx.left_path);
    CHECK_EQ(count, 1, "exactly one left-named record");
    count = proc_count_lines(0, "maps", ctx.right_path);
    CHECK_EQ(count, 0, "right record folded away");
    count = proc_count_lines(0, "smaps", merged_line_needle);
    CHECK_EQ(count, 1, "smaps shows merged record");

    /* Fork inheritance: a forked child sees the merged record too. */
    CHECK(pipe(ready_pipe) == 0 && pipe(done_pipe) == 0, "pipes for fork");
    child = fork();
    if (child == 0) {
        /* Child: verify own merged view, report the line count. */
        char needle[160];
        char buf[65536];
        int len;
        int hits = 0;
        char *line;

        close(ready_pipe[0]);
        close(done_pipe[0]);
        snprintf(needle, sizeof(needle), "%lx-%lx ", ctx.first, ctx.end);
        len = read_file("/proc/self/maps", buf, sizeof(buf));
        if (len > 0) {
            line = strtok(buf, "\n");
            while (line) {
                if (strstr(line, needle) && strstr(line, ctx.left_path))
                    hits++;
                line = strtok(NULL, "\n");
            }
        }
        pipe_write_u64(done_pipe[1], (uint64_t)hits);
        close(done_pipe[1]);
        close(ready_pipe[1]);
        _exit(0);
    }
    close(ready_pipe[1]);
    close(done_pipe[1]);
    track_child(child);
    waitpid(child, NULL, 0);
    pipe_read_u64(done_pipe[0], &merged_lines);
    close(ready_pipe[0]);
    close(done_pipe[0]);
    CHECK_EQ(merged_lines, 1, "fork child inherits merged record");

    /* Clear: back to two records. */
    PRCTL_OK("clear merge view", PR_YGGDRASIL_VMA_MERGE_VIEW, 0, 0, 0, 0);
    count = proc_count_lines(0, "maps", merged_line_needle);
    CHECK_EQ(count, 0, "merged record gone after clear");
    CHECK(maps_find(0, ctx.right_path, NULL, NULL, NULL) == 0,
          "right record visible after clear");

    munmap(m1, 4096);
    munmap(m0, 4096);
    unlink(ctx.left_path);
    unlink(ctx.right_path);
}

/* ------------------------------------------------------------------ */
/* Section: maps policy                                               */
/* ------------------------------------------------------------------ */

/*
 * Child holding one read-only mapping of `path`.
 * Signals 'R' on ready_pipe, then serves commands from ctl_pipe and answers
 * on ready_pipe: 'F' forks a grandchild (which inherits mapping and any
 * per-pid scope) and reports its pid as u64; 'Q' exits.
 */
static pid_t spawn_map_holder(const char *path, int ready_pipe[2],
                              int ctl_pipe[2])
{
    pid_t pid;

    if (pipe(ready_pipe) < 0 || pipe(ctl_pipe) < 0)
        return -1;

    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int fd;
        void *map;
        int running = 1;

        close(ready_pipe[0]);
        close(ctl_pipe[1]);
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            _exit(1);
        map = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE, fd, 0);
        if (map == MAP_FAILED)
            _exit(2);
        pipe_putc(ready_pipe[1], 'R');
        while (running) {
            int c = pipe_getc(ctl_pipe[0]);

            if (c == 'F') {
                pid_t g = fork();

                if (g == 0) {
                    /* Grandchild: keeps the mapping alive. */
                    while (1)
                        pause();
                }
                pipe_write_u64(ready_pipe[1], (uint64_t)g);
            } else {
                /* 'Q' or EOF releases the holder. */
                running = 0;
            }
        }
        munmap(map, 4096);
        close(fd);
        _exit(0);
    }
    close(ready_pipe[1]);
    close(ctl_pipe[0]);
    track_child(pid);
    pipe_getc(ready_pipe[0]);
    return pid;
}

static void test_maps_policy(void)
{
    char path[128];
    char other_path[128];
    pid_t pid;
    pid_t other;
    int rpipe[2];
    int cpipe[2];
    int rpipe2[2];
    int cpipe2[2];
    struct ygg_maps_rule rule;
    unsigned long start;
    unsigned long end;
    char perms[5];
    char range_key[64];
    char proc_path[128];
    const char *base;
    uint32_t total;
    uint32_t copied;
    int is_global;
    struct ygg_maps_rule rules[YGG_MAPS_MAX_RULES];
    int i;

    g_section = "maps";
    snprintf(path, sizeof(path), "%smaps_%d.bin", TEST_TMP_BASE,
             (int)getpid());
    snprintf(other_path, sizeof(other_path), "%smaps_other_%d.bin",
             TEST_TMP_BASE, (int)getpid());
    CHECK(write_fixture(path, "maps-sample", 11) == 0, "fixture file");
    CHECK(write_fixture(other_path, "other", 5) == 0, "other fixture");

    pid = spawn_map_holder(path, rpipe, cpipe);
    other = spawn_map_holder(other_path, rpipe2, cpipe2);
    CHECK(pid > 0, "holder process");
    CHECK(other > 0, "second holder process");
    if (pid <= 0 || other <= 0)
        return;

    CHECK(maps_find(pid, path, &start, &end, perms) == 0,
          "holder mapping found in maps");
    CHECK_EQ(memcmp(perms, "r--p", 4), 0, "mapping perms are r--p");
    snprintf(range_key, sizeof(range_key), "%lx-%lx", start, end);
    /* BASENAME matches the full basename exactly (not a substring), so derive
     * the operand from the fixture path instead of a hand-written prefix. */
    base = strrchr(path, '/');
    base = base ? base + 1 : path;

    /* --- validation --- */
    memset(&rule, 0, sizeof(rule));
    rule.action = YGG_MAPS_ACTION_HIDE;
    rule.match_kind = 99;
    CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) < 0 &&
          errno == EINVAL, "reject unknown match_kind");

    memset(&rule, 0, sizeof(rule));
    rule.action = 99;
    rule.match_kind = YGG_MAPS_MATCH_PATH_SUBSTR;
    snprintf(rule.pattern, sizeof(rule.pattern), "%s", path);
    CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) < 0 &&
          errno == EINVAL, "reject unknown action");

    /* GET on a pid with no scope reports zero rules. */
    CHECK(maps_get(1, pid, &total, &copied, NULL, NULL, 0) == 0,
          "get empty per-pid scope");
    CHECK_EQ(total, 0, "empty scope total_rules");

    /* --- per-pid HIDE by basename --- */
    memset(&rule, 0, sizeof(rule));
    rule.action = YGG_MAPS_ACTION_HIDE;
    rule.match_kind = YGG_MAPS_MATCH_PATH_BASENAME;
    snprintf(rule.pattern, sizeof(rule.pattern), "%s", base);
    CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) == 0,
          "add per-pid hide rule");
    CHECK(proc_count_lines(pid, "maps", path) == 0, "maps hides record");
    CHECK(proc_count_lines(pid, "smaps", path) == 0, "smaps hides record");

    /* Scope isolation: the other process keeps its mapping visible, and a
     * substring rule for it does not leak into the first pid's scope. */
    CHECK(proc_count_lines(other, "maps", other_path) > 0,
          "other process unaffected");

    /* map_files hides the covered range too. */
    snprintf(proc_path, sizeof(proc_path), "/proc/%d/map_files/%s", pid,
             range_key);
    {
        char target[256];
        errno = 0;
        CHECK(readlink_path(proc_path, target, sizeof(target)) < 0,
              "map_files link hidden");
    }

    /* GET round-trips the rule. */
    CHECK(maps_get(1, pid, &total, &copied, &is_global, rules,
                   YGG_MAPS_MAX_RULES) == 0, "get per-pid scope");
    CHECK_EQ(total, 1, "one rule stored");
    CHECK_EQ(copied, 1, "one rule copied");
    CHECK_EQ(is_global, 0, "scope not global");
    CHECK(rules[0].action == YGG_MAPS_ACTION_HIDE &&
          rules[0].match_kind == YGG_MAPS_MATCH_PATH_BASENAME &&
          !strcmp(rules[0].pattern, base), "rule round-trips");

    /* Clearing the pid scope restores visibility. */
    CHECK(maps_clear(1, pid) == 0, "clear per-pid scope");
    CHECK(proc_count_lines(pid, "maps", path) == 1, "maps record restored");
    CHECK(maps_get(1, pid, &total, &copied, NULL, NULL, 0) == 0 &&
          total == 0, "scope empty after clear");

    /* --- per-pid REWRITE_PERMS with match-perms predicate --- */
    memset(&rule, 0, sizeof(rule));
    rule.action = YGG_MAPS_ACTION_REWRITE_PERMS;
    rule.match_kind = YGG_MAPS_MATCH_PATH_SUBSTR;
    rule.rule_flags = YGG_MAPS_RULE_F_MATCH_PERMS;
    memcpy(rule.match_perms, "r--", 4);
    memcpy(rule.set_perms, "---", 4);
    snprintf(rule.pattern, sizeof(rule.pattern), "%s", path);
    CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) == 0,
          "add rewrite rule");
    CHECK(maps_find(pid, path, NULL, NULL, perms) == 0, "record still shown");
    CHECK_EQ(memcmp(perms, "---p", 4), 0, "perms rewritten to ---p");
    CHECK(proc_count_lines(pid, "smaps", path) == 1, "smaps shows record");

    /* REMOVE_RULE drops it. */
    CHECK(maps_add_rule_full(YGG_MAPS_OP_REMOVE_RULE, 1, pid, &rule) == 0,
          "remove rewrite rule");
    CHECK(maps_find(pid, path, NULL, NULL, perms) == 0, "record still shown");
    CHECK_EQ(memcmp(perms, "r--p", 4), 0, "perms restored after remove");

    /* --- global scope + most-specific-wins vs per-pid --- */
    CHECK(maps_add_rule_global(YGG_MAPS_ACTION_REWRITE_PERMS,
                               YGG_MAPS_MATCH_PATH_SUBSTR, path) == 0,
          "add global rewrite rule");
    CHECK(maps_find(pid, path, NULL, NULL, perms) == 0, "record shown");
    CHECK_EQ(memcmp(perms, "---p", 4), 0, "global rewrite applies");

    /* Per-pid rule overrides the global rewrite. */
    memset(&rule, 0, sizeof(rule));
    rule.action = YGG_MAPS_ACTION_REWRITE_PERMS;
    rule.match_kind = YGG_MAPS_MATCH_PATH_SUBSTR;
    memcpy(rule.set_perms, "rw-", 4);
    snprintf(rule.pattern, sizeof(rule.pattern), "%s", path);
    CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) == 0,
          "add per-pid override rule");
    CHECK(maps_find(pid, path, NULL, NULL, perms) == 0, "record shown");
    CHECK_EQ(memcmp(perms, "rw-p", 4), 0, "per-pid wins over global");

    CHECK(maps_clear_all() == 0, "clear all scopes");
    CHECK(maps_find(pid, path, NULL, NULL, perms) == 0, "record shown");
    CHECK_EQ(memcmp(perms, "r--p", 4), 0, "perms pristine after clear-all");

    /* --- ADDR_RANGE hiding --- */
    memset(&rule, 0, sizeof(rule));
    rule.action = YGG_MAPS_ACTION_HIDE;
    rule.match_kind = YGG_MAPS_MATCH_ADDR_RANGE;
    rule.addr_start = start + 1024;   /* overlaps [start, end) */
    rule.addr_end = start + 2048;
    CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) == 0,
          "add addr-range rule");
    CHECK(proc_count_lines(pid, "maps", path) == 0, "addr-range hides record");
    CHECK(maps_clear(1, pid) == 0, "clear pid scope");
    CHECK(proc_count_lines(pid, "maps", path) == 1, "record back");

    /* --- fork inheritance: the holder forks, grandchild inherits --- */
    memset(&rule, 0, sizeof(rule));
    rule.action = YGG_MAPS_ACTION_HIDE;
    rule.match_kind = YGG_MAPS_MATCH_PATH_SUBSTR;
    snprintf(rule.pattern, sizeof(rule.pattern), "%s", path);
    CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) == 0,
          "re-add hide rule before fork");

    CHECK(pipe_putc(cpipe[1], 'F') == 0, "ask holder to fork");
    {
        uint64_t raw_grandchild = (uint64_t)-1;
        pid_t grandchild;

        CHECK(pipe_read_u64(rpipe[0], &raw_grandchild) == 0,
              "grandchild pid reported");
        grandchild = (pid_t)raw_grandchild;
        CHECK(grandchild > 0, "valid grandchild pid");
        track_child(grandchild);
        CHECK(proc_count_lines(grandchild, "maps", path) == 0,
              "grandchild maps hide the record");
        CHECK(maps_get(1, grandchild, &total, &copied, NULL, NULL, 0) == 0,
              "get grandchild scope");
        CHECK_EQ(total, 1, "grandchild inherited one rule");
        kill(grandchild, SIGKILL);
        waitpid(grandchild, NULL, 0);
    }
    CHECK(maps_clear(1, pid) == 0, "clear holder scope");

    /* --- rule capacity: 32 rules max, then ENOSPC --- */
    CHECK(maps_clear(1, pid) == 0, "clear before capacity test");
    {
        int added = 0;

        for (i = 0; i < 40; i++) {
            memset(&rule, 0, sizeof(rule));
            rule.action = YGG_MAPS_ACTION_HIDE;
            rule.match_kind = YGG_MAPS_MATCH_PATH_SUBSTR;
            snprintf(rule.pattern, sizeof(rule.pattern), "cap%02d", i);
            if (maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) < 0)
                break;
            added++;
        }
        CHECK_EQ(added, YGG_MAPS_MAX_RULES, "fills 32 rules");
        CHECK(maps_add_rule_full(YGG_MAPS_OP_ADD_RULE, 1, pid, &rule) < 0 &&
              errno == ENOSPC, "33rd rule rejected with ENOSPC");
        CHECK(maps_clear(1, pid) == 0, "clear after capacity test");
    }

    /* --- ALL_SCOPES validation --- */
    {
        struct ygg_maps_request req;

        maps_init_request(&req, YGG_MAPS_OP_ADD_RULE, 0, 0);
        req.flags |= YGG_MAPS_F_ALL_SCOPES;
        req.rule.action = YGG_MAPS_ACTION_HIDE;
        req.rule.match_kind = YGG_MAPS_MATCH_PATH_SUBSTR;
        snprintf(req.rule.pattern, sizeof(req.rule.pattern), "x");
        CHECK(maps_send(&req) < 0 && errno == EINVAL,
              "ALL_SCOPES only valid with CLEAR");

        maps_init_request(&req, YGG_MAPS_OP_CLEAR, 1, pid);
        req.flags |= YGG_MAPS_F_ALL_SCOPES;
        CHECK(maps_send(&req) < 0 && errno == EINVAL,
              "ALL_SCOPES plus TARGET_PID rejected");
    }

    /* --- merge interplay: hide rule drops the merge view --- */
    {
        void *m0 = NULL;
        void *m1 = NULL;
        char left[128];
        char right[128];
        unsigned long pair_first;
        unsigned long pair_end;
        char merged_key[64];

        snprintf(left, sizeof(left), "%sml_%d.bin", TEST_TMP_BASE,
                 (int)getpid());
        snprintf(right, sizeof(right), "%smr_%d.bin", TEST_TMP_BASE,
                 (int)getpid());
        CHECK(map_adjacent_pair(left, right, &m0, &m1) == 0,
              "map pair for merge interplay");
        if (m0 && m1) {
            pair_first = (unsigned long)m0;
            pair_end = (unsigned long)m1 + 4096;
            snprintf(merged_key, sizeof(merged_key), "%lx-%lx",
                     pair_first, pair_end);

            CHECK(yprctl(PR_YGGDRASIL_VMA_MERGE_VIEW, pair_first, pair_end,
                         0, 0) == 0, "merge pair");
            CHECK(proc_count_lines(0, "maps", merged_key) == 1,
                  "merged record visible");

            /* A hide rule on the left file makes merge-view step aside. */
            CHECK(maps_add_rule_global(YGG_MAPS_ACTION_HIDE,
                                       YGG_MAPS_MATCH_PATH_SUBSTR,
                                       left) == 0, "add hide rule");
            CHECK(proc_count_lines(0, "maps", merged_key) == 0,
                  "merged record dropped when hide rule fires");
            CHECK(proc_count_lines(0, "maps", left) == 0,
                  "left hidden in per-VMA output");
            CHECK(proc_count_lines(0, "maps", right) == 1,
                  "right visible in per-VMA output");

            CHECK(maps_clear_all() == 0, "clear all");
            CHECK(proc_count_lines(0, "maps", merged_key) == 1,
                  "merged record back after clear");
            yprctl(PR_YGGDRASIL_VMA_MERGE_VIEW, 0, 0, 0, 0);
            munmap(m1, 4096);
            munmap(m0, 4096);
            unlink(left);
            unlink(right);
        }
    }

    maps_clear_all();
    pipe_putc(cpipe2[1], 'x');
    pipe_putc(cpipe[1], 'x');
    waitpid(other, NULL, 0);
    waitpid(pid, NULL, 0);
    unlink(path);
    unlink(other_path);
}

/* ------------------------------------------------------------------ */
/* Section: hide_dbg                                                  */
/* ------------------------------------------------------------------ */

/* Raw ptrace entry so the hook path is always exercised. */
static long ptrace_syscall(long request, long pid, long addr, long data)
{
    return syscall(SYS_ptrace, request, pid, addr, data);
}

/*
 * Child that raises SIGSTOP on demand ('S') so the parent can observe its
 * /proc status State field, and that holds a ptrace-seized grandchild on
 * demand ('T') so the parent can observe State + TracerPid rewriting.
 * The holder itself seizes the grandchild so the tracer relationship is
 * visible to /proc regardless of who reads the status file. Commands come
 * over ctl_pipe; the grandchild pid arrives as a u64 over ready_pipe.
 */
static pid_t spawn_dbg_target(int ready_pipe[2], int ctl_pipe[2])
{
    pid_t pid;

    if (pipe(ready_pipe) < 0 || pipe(ctl_pipe) < 0)
        return -1;

    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int running = 1;

        close(ready_pipe[0]);
        close(ctl_pipe[1]);
        pipe_putc(ready_pipe[1], 'R');
        while (running) {
            int c = pipe_getc(ctl_pipe[0]);

            if (c == 'S')
                raise(SIGSTOP);
            else if (c == 'T') {
                pid_t g = fork();

                if (g == 0) {
                    /* Grandchild to be traced by the holder. */
                    while (1)
                        pause();
                }
                ptrace_syscall(PTRACE_SEIZE, g, 0, 0);
                ptrace_syscall(PTRACE_INTERRUPT, g, 0, 0);
                waitpid(g, NULL, __WALL);
                pipe_write_u64(ready_pipe[1], (uint64_t)g);
            } else {
                running = 0;
            }
        }
        _exit(0);
    }
    close(ready_pipe[1]);
    close(ctl_pipe[0]);
    track_child(pid);
    pipe_getc(ready_pipe[0]);
    return pid;
}

/*
 * Stop the holder and wait until the kernel has actually stopped it. Do not
 * poll /proc/<pid>/status for 'T' here: a hide_dbg rule rewrites that very
 * line to 'S' while active, so the poll would spin forever under the feature
 * being tested. waitpid(WUNTRACED) reports the kernel's own stop event,
 * which the hook never touches.
 */
static void stop_and_wait(pid_t pid)
{
    int status;

    if (kill(pid, SIGSTOP) < 0)
        return;
    while (waitpid(pid, &status, WUNTRACED) < 0 && errno == EINTR)
        ;
}

/*
 * Resume the holder and wait until it has left the stopped state. Callers
 * disable any active hide_dbg rule first, so the State line read here is the
 * kernel's truth; 't' (tracing stop) is also treated as stopped.
 */
static void continue_proc(pid_t pid)
{
    char state[32];

    kill(pid, SIGCONT);
    while (1) {
        if (status_field(pid, "State", state, sizeof(state)) == 0 &&
            state[0] != 'T' && state[0] != 't')
            break;
        usleep(50000);
    }
}

static void test_hide_dbg(void)
{
    struct ygg_hide_dbg_request req = {};
    pid_t pid;
    int rpipe[2];
    int cpipe[2];
    char state[32];
    char tracer[32];

    g_section = "dbg";
    pid = spawn_dbg_target(rpipe, cpipe);
    CHECK(pid > 0, "dbg target running");
    if (pid <= 0)
        return;

    /* Baseline: a stopped task shows T (stopped). */
    stop_and_wait(pid);
    CHECK(status_field(pid, "State", state, sizeof(state)) == 0 &&
          state[0] == 'T', "baseline shows stopped state");

    /* Global enable rewrites the state line to sleeping. */
    req.enable = 1;
    PRCTL_OK("enable hide_dbg globally", PR_YGGDRASIL_HIDE_DBG, &req, 0, 0, 0);
    CHECK(status_field(pid, "State", state, sizeof(state)) == 0 &&
          !strncmp(state, "S (sleeping)", 12), "stopped rewritten to sleeping");

    /* Global disable restores the truth. */
    req.enable = 0;
    PRCTL_OK("disable hide_dbg globally", PR_YGGDRASIL_HIDE_DBG, &req, 0, 0, 0);
    CHECK(status_field(pid, "State", state, sizeof(state)) == 0 &&
          state[0] == 'T', "stopped visible again");
    continue_proc(pid);

    /* Per-uid rule: enable for the test's own uid only. */
    req.enable = 1;
    req.flags = YGG_HIDE_DBG_F_TARGET_UID;
    req.uid = (uint32_t)getuid();
    PRCTL_OK("enable hide_dbg for uid", PR_YGGDRASIL_HIDE_DBG, &req, 0, 0, 0);
    stop_and_wait(pid);
    CHECK(status_field(pid, "State", state, sizeof(state)) == 0 &&
          !strncmp(state, "S (sleeping)", 12), "uid rule rewrites state");
    /* Drop the rule; the stopped state becomes visible again. */
    req.enable = 0;
    PRCTL_OK("disable hide_dbg for uid", PR_YGGDRASIL_HIDE_DBG, &req, 0, 0, 0);
    CHECK(status_field(pid, "State", state, sizeof(state)) == 0 &&
          state[0] == 'T', "uid rule removed shows truth");
    continue_proc(pid);

    /* TracerPid rewrite: hold a ptrace-attached grandchild. */
    CHECK(pipe_putc(cpipe[1], 'T') == 0, "spawn tracee grandchild");
    {
        uint64_t raw_tracee = (uint64_t)-1;
        pid_t tracee;

        CHECK(pipe_read_u64(rpipe[0], &raw_tracee) == 0, "tracee pid");
        tracee = (pid_t)raw_tracee;
        track_child(tracee);
        CHECK(tracee > 0, "valid tracee pid");

        /* The holder seized the tracee: TracerPid points at the holder. */
        CHECK(status_field(tracee, "TracerPid", tracer, sizeof(tracer)) == 0,
              "read TracerPid");
        {
            long tracer_pid = strtol(tracer, NULL, 10);

            CHECK(tracer_pid == (long)pid, "baseline TracerPid is holder");
        }

        /* Global spoof rewrites TracerPid to 0. */
        memset(&req, 0, sizeof(req));
        req.enable = 1;
        PRCTL_OK("enable hide_dbg again", PR_YGGDRASIL_HIDE_DBG, &req, 0, 0, 0);
        CHECK(status_field(tracee, "TracerPid", tracer, sizeof(tracer)) == 0 &&
              strtol(tracer, NULL, 10) == 0, "TracerPid rewritten to 0");
        CHECK(status_field(tracee, "State", state, sizeof(state)) == 0 &&
              !strncmp(state, "S (sleeping)", 12),
              "tracing-stop rewritten to sleeping");

        req.enable = 0;
        PRCTL_OK("disable hide_dbg again", PR_YGGDRASIL_HIDE_DBG, &req, 0, 0, 0);
        CHECK(status_field(tracee, "TracerPid", tracer, sizeof(tracer)) == 0 &&
              strtol(tracer, NULL, 10) == (long)pid,
              "TracerPid visible again");
    }

    /* Validation: unknown flag rejected. */
    memset(&req, 0, sizeof(req));
    req.enable = 1;
    req.flags = 0x8000;
    PRCTL_ERR("reject unknown flag", EINVAL, PR_YGGDRASIL_HIDE_DBG, &req, 0,
              0, 0);

    pipe_putc(cpipe[1], 'Q');
    waitpid(pid, NULL, 0);
}

/* ------------------------------------------------------------------ */
/* Section: ptrace spoof                                              */
/* ------------------------------------------------------------------ */

struct ptrace_iovec_local {
    uint64_t iov_base;
    uint64_t iov_len;
};

/*
 * Query NT_ARM_HW_BREAK / NT_ARM_HW_WATCH regsets of `tid` via
 * PTRACE_GETREGSET. Returns 0 on success and fills dbg_info plus regs.
 * The count stops at the first all-zero entry: the module's spoofed answer
 * always reports the full requested iov_len, while the real kernel reports
 * only the populated entries.
 */
static int get_hwdebug_regset(pid_t tid, int nt, uint32_t *dbg_info,
                              uint64_t *addrs, uint32_t *ctrls, int max,
                              uint32_t *count)
{
    struct ptrace_iovec_local iov;
    struct {
        uint32_t info;
        uint32_t pad;
        struct {
            uint64_t addr;
            uint32_t ctrl;
            uint32_t pad2;
        } regs[16];
    } state;
    long ret;
    uint32_t n;
    uint32_t i;

    memset(&state, 0, sizeof(state));
    iov.iov_base = (uint64_t)(uintptr_t)&state;
    iov.iov_len = sizeof(state);
    errno = 0;
    ret = ptrace_syscall(PTRACE_GETREGSET, tid, nt, (long)(uintptr_t)&iov);
    if (ret < 0)
        return -1;

    *dbg_info = state.info;
    if (iov.iov_len <= 8) {
        *count = 0;
        return 0;
    }
    n = (uint32_t)((iov.iov_len - 8) / 16);
    if (n > 16)
        n = 16;
    *count = 0;
    for (i = 0; i < n && (int)i < max; i++) {
        if (!state.regs[i].addr && !state.regs[i].ctrl)
            break;
        addrs[i] = state.regs[i].addr;
        ctrls[i] = state.regs[i].ctrl;
        (*count)++;
    }
    return 0;
}

/* Set NT_ARM_HW_* regsets via PTRACE_SETREGSET. */
static int set_hwdebug_regset(pid_t tid, int nt, uint64_t addr, uint32_t ctrl)
{
    struct ptrace_iovec_local iov;
    struct {
        uint32_t info;
        uint32_t pad;
        struct {
            uint64_t addr;
            uint32_t ctrl;
            uint32_t pad2;
        } regs[1];
    } state;

    memset(&state, 0, sizeof(state));
    state.regs[0].addr = addr;
    state.regs[0].ctrl = ctrl;
    iov.iov_base = (uint64_t)(uintptr_t)&state;
    iov.iov_len = 8 + 16;
    errno = 0;
    return ptrace_syscall(PTRACE_SETREGSET, tid, nt,
                          (long)(uintptr_t)&iov) < 0 ? -1 : 0;
}

static void test_ptrace_spoof(void)
{
    struct ygg_ptrace_spoof_request req = {};
    pid_t pid;
    int rpipe[2];
    int cpipe[2];
    uint32_t info;
    uint64_t addrs[16];
    uint32_t ctrls[16];
    uint32_t count;

    g_section = "ptrace";
    pid = spawn_dbg_target(rpipe, cpipe);
    CHECK(pid > 0, "ptrace target running");
    if (pid <= 0)
        return;

    /* GETREGSET needs an attached tracer relationship; the tracee stays in
     * ptrace-stop until the final DETACH. */
    CHECK(ptrace_syscall(PTRACE_ATTACH, pid, 0, 0) == 0, "PTRACE_ATTACH");
    waitpid(pid, NULL, 0);

    /* Baseline: real empty hardware-debug regsets. */
    CHECK(get_hwdebug_regset(pid, NT_ARM_HW_BREAK, &info, addrs, ctrls, 16,
                             &count) == 0, "read HW_BREAK regset");
    CHECK_EQ(count, 0, "baseline has zero breakpoints");

    /* Per-pid spoof: the target's regsets become virtual. */
    req.enable = 1;
    req.flags = YGG_PTRACE_SPOOF_F_TARGET_PID;
    req.pid = (uint32_t)pid;
    PRCTL_OK("enable ptrace spoof for pid", PR_YGGDRASIL_PTRACE_SPOOF, &req,
             0, 0, 0);

    /* SETREGSET stores into the fake state. */
    CHECK(set_hwdebug_regset(pid, NT_ARM_HW_BREAK, 0x12340000, 0x5) == 0,
          "set fake breakpoint");
    CHECK(get_hwdebug_regset(pid, NT_ARM_HW_BREAK, &info, addrs, ctrls, 16,
                             &count) == 0, "read spoofed HW_BREAK regset");
    CHECK_EQ(count, 1, "one fake breakpoint");
    CHECK_EQ(addrs[0], 0x12340000, "fake breakpoint address");
    CHECK_EQ(ctrls[0], 0x5, "fake breakpoint control");

    /* Watchpoints are a separate virtual regset. */
    CHECK(set_hwdebug_regset(pid, NT_ARM_HW_WATCH, 0xdead0000, 0x7) == 0,
          "set fake watchpoint");
    CHECK(get_hwdebug_regset(pid, NT_ARM_HW_WATCH, &info, addrs, ctrls, 16,
                             &count) == 0, "read spoofed HW_WATCH regset");
    CHECK_EQ(count, 1, "one fake watchpoint");
    CHECK_EQ(addrs[0], 0xdead0000, "fake watchpoint address");
    CHECK_EQ(ctrls[0], 0x7, "fake watchpoint control");
    /* Breakpoints were not disturbed by the watch write. */
    CHECK(get_hwdebug_regset(pid, NT_ARM_HW_BREAK, &info, addrs, ctrls, 16,
                             &count) == 0, "re-read HW_BREAK regset");
    CHECK_EQ(count, 1, "breakpoint regset independent");

    /* Disable restores real (empty) regsets. */
    req.enable = 0;
    PRCTL_OK("disable ptrace spoof for pid", PR_YGGDRASIL_PTRACE_SPOOF, &req,
             0, 0, 0);
    CHECK(get_hwdebug_regset(pid, NT_ARM_HW_BREAK, &info, addrs, ctrls, 16,
                             &count) == 0, "read HW_BREAK after unspoof");
    CHECK_EQ(count, 0, "regsets empty after unspoof");

    /* Validation. */
    memset(&req, 0, sizeof(req));
    req.enable = 1;
    req.flags = YGG_PTRACE_SPOOF_F_TARGET_PID | 0x8000;
    req.pid = (uint32_t)pid;
    PRCTL_ERR("reject unknown flag", EINVAL, PR_YGGDRASIL_PTRACE_SPOOF, &req,
              0, 0, 0);
    req.flags = YGG_PTRACE_SPOOF_F_TARGET_PID;
    req.pid = 0;
    PRCTL_ERR("reject pid zero", EINVAL, PR_YGGDRASIL_PTRACE_SPOOF, &req, 0,
              0, 0);

    ptrace_syscall(PTRACE_DETACH, pid, 0, 0);
    pipe_putc(cpipe[1], 'Q');
    waitpid(pid, NULL, 0);
}

/* ------------------------------------------------------------------ */
/* Section: label spoof                                               */
/* ------------------------------------------------------------------ */

static int fgetxattr_label(int fd, char *out, size_t size)
{
    ssize_t n = syscall(SYS_fgetxattr, fd, "security.selinux", out, size);

    if (n < 0)
        return -1;
    if ((size_t)n < size)
        out[n] = '\0';
    else
        out[size - 1] = '\0';
    return (int)n;
}

static int peersec_label(int fd, char *out, size_t size)
{
    socklen_t len = size;

    if (getsockopt(fd, SOL_SOCKET, SO_PEERSEC, out, &len) < 0)
        return -1;
    if (len < size)
        out[len] = '\0';
    else
        out[size - 1] = '\0';
    return (int)len;
}

/* Widen a process-form role "r" to object form, like the module does. */
static void normalize_role(char *ctx, size_t size)
{
    char *c1 = strchr(ctx, ':');
    char *c2 = c1 ? strchr(c1 + 1, ':') : NULL;

    if (!c1 || !c2)
        return;
    if ((size_t)(c2 - c1) != 2 || c1[1] != 'r')
        return;
    if (strlen(ctx) + 7 + 1 > size)
        return;
    /* "r" (1 char) becomes "object_r" (8 chars): the tail from c2 through the
     * NUL shifts forward by exactly 7, so the old NUL is overwritten too. */
    memmove(c2 + 7, c2, strlen(c2) + 1);
    memcpy(c1 + 1, "object_r", 8);
}

static void test_label_spoof(void)
{
    struct ygg_label_spoof_request req = {};
    int sv[2];
    char current[512];
    char current_obj[512];
    char baseline[512];
    char observed[512];
    int fd;
    int len;

    g_section = "label";
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0, "socketpair");

    CHECK(read_file("/proc/self/attr/current", current, sizeof(current)) > 0,
          "read own context");
    /* Strip trailing newline; compute the object form. */
    {
        char *nl = strchr(current, '\n');

        if (nl)
            *nl = '\0';
    }
    snprintf(current_obj, sizeof(current_obj), "%s", current);
    normalize_role(current_obj, sizeof(current_obj));

    /* Baseline: global spoofing off, raw label of our own socket. */
    req.enable = 0;
    PRCTL_OK("unspoof all", PR_YGGDRASIL_LABEL_SPOOF, &req, 0, 0, 0);
    len = fgetxattr_label(sv[0], baseline, sizeof(baseline));
    CHECK(len > 0, "baseline fgetxattr works");
    if (len > 0)
        printf("       baseline socket label: %s\n", baseline);

    /* Per-pid spoof: every answer becomes our own context. */
    req.enable = 1;
    req.flags = YGG_LABEL_SPOOF_F_TARGET_PID;
    req.pid = (uint32_t)getpid();
    PRCTL_OK("spoof for own pid", PR_YGGDRASIL_LABEL_SPOOF, &req, 0, 0, 0);
    len = fgetxattr_label(sv[0], observed, sizeof(observed));
    CHECK(len > 0, "spoofed fgetxattr works");
    if (len > 0) {
        printf("       spoofed socket label: %s (own object form: %s)\n",
               observed, current_obj);
        CHECK(!strcmp(observed, baseline) ||
              !strcmp(observed, current_obj),
              "xattr answer is baseline or own context");
    }

    /* SO_PEERSEC path with a full-size buffer. */
    len = peersec_label(sv[0], observed, sizeof(observed));
    CHECK(len > 0, "spoofed SO_PEERSEC works");
    if (len > 0)
        CHECK(!strcmp(observed, baseline) || !strcmp(observed, current),
              "peersec answer is baseline or own context");

    /* Short-buffer probe must report -ERANGE with a length hint. */
    {
        char small[512];
        socklen_t slen = 4;
        int rc = getsockopt(sv[0], SOL_SOCKET, SO_PEERSEC, small, &slen);

        check_begin();
        if (rc == -1 && errno == ERANGE && slen > 4)
            check_pass("short SO_PEERSEC reports ERANGE with hint");
        else
            check_fail("short SO_PEERSEC reports ERANGE with hint",
                       "line %d: rc=%d errno=%d len=%u", __LINE__, rc,
                       errno, slen);
    }

    /* Size probe and read must agree while spoofed. */
    {
        ssize_t probe = syscall(SYS_fgetxattr, sv[0], "security.selinux",
                                NULL, 0);

        len = fgetxattr_label(sv[0], observed, sizeof(observed));
        check_begin();
        if (probe == (ssize_t)(strlen(observed) + 1))
            check_pass("probe length matches read length");
        else
            check_fail("probe length matches read length",
                       "line %d: probe=%zd read=%d", __LINE__, probe, len);
    }

    /* A clean fd keeps its own answer regardless of spoofing. */
    fd = open("/system/etc/hosts", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        char clean_base[512];
        char clean_spoofed[512];

        fgetxattr_label(fd, clean_base, sizeof(clean_base));
        fgetxattr_label(fd, clean_spoofed, sizeof(clean_spoofed));
        CHECK(!strcmp(clean_base, clean_spoofed),
              "clean label passes through unchanged");
        close(fd);
    }

    /* Control-plane validation. */
    memset(&req, 0, sizeof(req));
    req.enable = 1;
    req.flags = YGG_LABEL_SPOOF_F_TARGET_UID | YGG_LABEL_SPOOF_F_TARGET_PID;
    req.uid = 2000;
    req.pid = (uint32_t)getpid();
    PRCTL_ERR("reject uid+pid together", EINVAL, PR_YGGDRASIL_LABEL_SPOOF,
              &req, 0, 0, 0);
    req.flags = YGG_LABEL_SPOOF_F_TARGET_PID;
    req.uid = 0;
    req.pid = 0;
    PRCTL_ERR("reject pid zero", EINVAL, PR_YGGDRASIL_LABEL_SPOOF, &req, 0,
              0, 0);
    req.flags = 0;
    req.pid = (uint32_t)getpid();
    PRCTL_ERR("reject pid without flag", EINVAL, PR_YGGDRASIL_LABEL_SPOOF,
              &req, 0, 0, 0);

    /* Leave the feature globally enabled, as it is at module load. */
    memset(&req, 0, sizeof(req));
    req.enable = 1;
    PRCTL_OK("restore global spoof", PR_YGGDRASIL_LABEL_SPOOF, &req, 0, 0, 0);

    close(sv[0]);
    close(sv[1]);
}

/* ------------------------------------------------------------------ */
/* Section: hwbp (attach/replace trampoline + observe)                */
/* ------------------------------------------------------------------ */

/*
 * Trampoline contract (attach mode): the kernel hands control to the
 * trampoline with x0/x1 replaced by the rule's values and LR set to the
 * original target, so the trampoline can continue execution.
 */
struct hwbp_child_args {
    volatile unsigned long target_fn;
    volatile unsigned long trampoline_fn;
    volatile unsigned long observed_magic;
    volatile unsigned long replaced_x0;
};

static volatile struct hwbp_child_args *g_hwbp_args;

static __attribute__((noinline)) void hwbp_target_fn(long a0, long a1)
{
    (void)a0;
    (void)a1;
    __asm__ __volatile__("" ::: "memory");
}

static __attribute__((noinline)) void hwbp_trampoline(void)
{
    unsigned long x0;
    unsigned long x1;

    __asm__ __volatile__("mov %0, x0\n"
                         "mov %1, x1\n"
                         : "=r"(x0), "=r"(x1)::);
    g_hwbp_args->observed_magic = 0x59474250;
    g_hwbp_args->replaced_x0 = x0;
    (void)x1;
}

/*
 * Child exposing target/trampoline addresses to the parent and calling the
 * target in a loop so installed breakpoints keep firing. Results are
 * shared through the MAP_SHARED `args` page, so the parent sees the
 * trampoline's writes live.
 */
static pid_t spawn_hwbp_child(struct hwbp_child_args *args)
{
    pid_t pid;
    int rpipe[2];
    int cpipe[2];
    uint64_t val = 0;
    int i;

    if (pipe(rpipe) < 0 || pipe(cpipe) < 0)
        return -1;
    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        close(rpipe[0]);
        close(cpipe[1]);
        args->target_fn = (unsigned long)(uintptr_t)hwbp_target_fn;
        args->trampoline_fn = (unsigned long)(uintptr_t)hwbp_trampoline;
        args->observed_magic = 0;
        args->replaced_x0 = 0;
        pipe_putc(rpipe[1], 'R');
        pipe_getc(cpipe[0]);
        for (i = 0; i < 60000; i++) {
            hwbp_target_fn(0, 0);
            usleep(1000);
        }
        close(rpipe[1]);
        close(cpipe[0]);
        _exit(0);
    }
    close(rpipe[1]);
    close(cpipe[0]);
    track_child(pid);
    pipe_getc(rpipe[0]);
    close(rpipe[0]);
    close(cpipe[1]);
    /* The addresses are identical across fork: resolve them locally. */
    (void)val;
    args->target_fn = (unsigned long)(uintptr_t)hwbp_target_fn;
    args->trampoline_fn = (unsigned long)(uintptr_t)hwbp_trampoline;
    return pid;
}

static void test_hwbp(void)
{
    /*
     * Shared page: fork children get COW copies of ordinary memory, so the
     * parent could never observe the trampoline's writes. MAP_SHARED keeps
     * one live page across the fork.
     */
    struct hwbp_child_args *args = (struct hwbp_child_args *)mmap(
        NULL, sizeof(struct hwbp_child_args), PROT_READ | PROT_WRITE,
        MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    pid_t pid;
    struct ygg_hwbp_request req;
    struct ygg_hwbp_observe_request obs;
    static struct {
        struct ygg_hwbp_event_header header;
        uint8_t stack[256];
    } event;
    struct ygg_hwbp_read_event_request rreq;
    long ret;

    g_section = "hwbp";
    CHECK(args != MAP_FAILED, "shared args page");
    if (args == MAP_FAILED)
        return;
    memset((void *)args, 0, sizeof(*args));
    g_hwbp_args = args;
    pid = spawn_hwbp_child(args);
    CHECK(pid > 0, "hwbp child running");
    if (pid <= 0)
        return;

    /* --- attach mode with register replacement --- */
    memset(&req, 0, sizeof(req));
    req.target_addr = args->target_fn;
    req.trampoline_addr = args->trampoline_fn;
    req.target_pid = (uint32_t)pid;
    req.x_mask = 0x1;            /* replace x0 */
    req.x_values[0] = 0x1122334455667788ULL;
    PRCTL_OK("attach hwbp", PR_YGGDRASIL_HWBP_ATTACH, &req, 0, 0, 0);

    /* Give the child a moment to run the target function. */
    usleep(200000);
    CHECK_EQ(args->observed_magic, 0x59474250UL, "trampoline ran");
    CHECK_EQ(args->replaced_x0, 0x1122334455667788UL, "x0 was replaced");

    /* --- detach --- */
    memset(&req, 0, sizeof(req));
    req.target_pid = (uint32_t)pid;
    PRCTL_OK("detach all rules for pid", PR_YGGDRASIL_HWBP_DETACH, &req, 0,
             0, 0);
    args->observed_magic = 0;

    /* --- replace mode: trampoline without re-entry --- */
    memset(&req, 0, sizeof(req));
    req.target_addr = args->target_fn;
    req.trampoline_addr = args->trampoline_fn;
    req.target_pid = (uint32_t)pid;
    PRCTL_OK("replace hwbp", PR_YGGDRASIL_HWBP_REPLACE, &req, 0, 0, 0);
    usleep(200000);
    CHECK_EQ(args->observed_magic, 0x59474250UL, "replace mode trampoline ran");
    memset(&req, 0, sizeof(req));
    req.target_pid = (uint32_t)pid;
    PRCTL_OK("detach replace rule", PR_YGGDRASIL_HWBP_DETACH, &req, 0, 0, 0);

    /* --- observe mode: execute breakpoint on the target function --- */
    memset(&obs, 0, sizeof(obs));
    obs.target_addr = args->target_fn;
    obs.target_pid = (uint32_t)pid;
    obs.mode = YGG_HWBP_MODE_X;
    obs.stack_size = 256;
    PRCTL_OK("observe execute breakpoint", PR_YGGDRASIL_HWBP_OBSERVE, &obs,
             0, 0, 0);
    usleep(300000);

    memset(&rreq, 0, sizeof(rreq));
    rreq.buffer = &event;
    rreq.buffer_size = sizeof(event);
    errno = 0;
    ret = prctl(PR_YGGDRASIL_HWBP_READ_EVENT, &rreq, 0, 0, 0);
    CHECK(ret >= 0, "read observe event");
    if (ret >= 0) {
        CHECK_EQ(event.header.magic, (uint32_t)YGG_HWBP_EVENT_MAGIC,
                 "event magic");
        CHECK_EQ(event.header.mode, (uint32_t)YGG_HWBP_MODE_X, "event mode");
        CHECK_EQ(event.header.watched_addr, (uint64_t)args->target_fn,
                 "event watched address");
        CHECK(event.header.stack_copied_size > 0, "event captured stack");
    }

    memset(&obs, 0, sizeof(obs));
    obs.target_pid = (uint32_t)pid;
    PRCTL_OK("unobserve execute rule", PR_YGGDRASIL_HWBP_UNOBSERVE, &obs, 0,
             0, 0);

    /* --- validation --- */
    memset(&req, 0, sizeof(req));
    PRCTL_ERR("reject zero target", EINVAL, PR_YGGDRASIL_HWBP_ATTACH, &req,
              0, 0, 0);
    memset(&obs, 0, sizeof(obs));
    obs.target_addr = 0x1000;
    obs.target_pid = (uint32_t)pid;
    obs.mode = 99;
    PRCTL_ERR("reject unknown mode", EINVAL, PR_YGGDRASIL_HWBP_OBSERVE,
              &obs, 0, 0, 0);

    kill(pid, SIGKILL);
    waitpid(pid, NULL, 0);
}

/* ------------------------------------------------------------------ */
/* Section: syscall monitor                                           */
/* ------------------------------------------------------------------ */

static void syscall_rule_init(struct ygg_syscall_rule_request *req)
{
    memset(req, 0, sizeof(*req));
    req->size = YGG_SYSCALL_RULE_REQUEST_SIZE;
    req->version = YGG_SYSCALL_ABI_VERSION;
    req->op = YGG_SYSCALL_RULE_SET;
}

/* Read one syscall event record (non-listener); 0 on success. */
static int syscall_read_event(struct ygg_syscall_event_record *record,
                              struct ygg_syscall_backtrace_event_record *bt)
{
    struct ygg_syscall_read_event_request req;

    memset(&req, 0, sizeof(req));
    req.size = YGG_SYSCALL_READ_REQUEST_SIZE;
    req.version = YGG_SYSCALL_ABI_VERSION;
    req.buffer = (uint64_t)(uintptr_t)record;
    req.buffer_size = sizeof(*record);
    if (bt) {
        req.backtrace_buffer = (uint64_t)(uintptr_t)bt;
        req.backtrace_buffer_size = sizeof(*bt);
    }
    errno = 0;
    if (prctl(PR_YGGDRASIL_SYSCALL_READ_EVENT, &req, 0, 0, 0) < 0)
        return -1;
    return 0;
}

static long syscall_rule_set(struct ygg_syscall_rule_request *req)
{
    errno = 0;
    return prctl(PR_YGGDRASIL_SYSCALL_CONFIG, req, 0, 0, 0);
}

static long syscall_clear_rules(void)
{
    struct ygg_syscall_rule_request req;

    syscall_rule_init(&req);
    req.op = YGG_SYSCALL_RULE_CLEAR;
    req.syscall_nr = YGG_SYSCALL_NR_ALL;
    return syscall_rule_set(&req);
}

/*
 * Child performing syscalls on command over ctl_pipe, answering results as
 * u64s over ready_pipe. Commands:
 *   'G' getpid          -> pid value
 *   'W' write(8 bytes)  -> bytes written
 *   'O' openat(fixture) -> fd
 *   'B' openat(ptr 1)   -> errno
 *   'U' setuid(2000)    -> 0 on success
 *   'M' mkdirat(dir)    -> 0 or errno  (symbolic mode/flags formatting)
 *   'S' newfstatat      -> 0 or errno  (struct stat output capture)
 *   'L' readlinkat      -> length/errno
 *   'P' mprotect        -> 0 or errno  (scalar-only signature)
 *   'Q' quit
 */
static pid_t spawn_syscall_child(const char *fixture, int ready_pipe[2],
                                 int ctl_pipe[2])
{
    pid_t pid;
    char mkdir_target[192];
    char link_target[192];

    snprintf(mkdir_target, sizeof(mkdir_target), "%s.dir", fixture);
    snprintf(link_target, sizeof(link_target), "%s.sym", fixture);
    if (pipe(ready_pipe) < 0 || pipe(ctl_pipe) < 0)
        return -1;
    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int sink = open("/dev/null", O_WRONLY | O_CLOEXEC);
        int running = 1;

        close(ready_pipe[0]);
        close(ctl_pipe[1]);
        pipe_putc(ready_pipe[1], 'R');
        while (running) {
            int c = pipe_getc(ctl_pipe[0]);

            switch (c) {
            case 'G':
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)syscall(SYS_getpid));
                break;
            case 'W': {
                static const char msg[] = "ARGUMENT";  /* 8 bytes */

                errno = 0;
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)syscall(SYS_write, sink, msg,
                                                 sizeof(msg) - 1));
                break;
            }
            case 'O': {
                long fd = syscall(SYS_openat, AT_FDCWD, fixture,
                                  O_RDONLY | O_CLOEXEC, 0);

                if (fd >= 0)
                    syscall(SYS_close, fd);
                pipe_write_u64(ready_pipe[1], (uint64_t)fd);
                break;
            }
            case 'B': {
                long r = syscall(SYS_openat, AT_FDCWD,
                                 (const char *)(uintptr_t)1, O_RDONLY, 0);

                pipe_write_u64(ready_pipe[1],
                               r < 0 ? (uint64_t)(uint32_t)errno : 0);
                break;
            }
            case 'U':
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)(uint32_t)(syscall(SYS_setuid, 2000)
                                                     ? errno : 0));
                break;
            case 'M': {
                long r = syscall(SYS_mkdirat, AT_FDCWD, mkdir_target, 0777);

                if (r == 0)
                    syscall(SYS_unlinkat, AT_FDCWD, mkdir_target,
                            AT_REMOVEDIR);
                pipe_write_u64(ready_pipe[1],
                               r < 0 ? (uint64_t)(uint32_t)errno : 0);
                break;
            }
            case 'S': {
                struct stat st;

                memset(&st, 0, sizeof(st));
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)(uint32_t)(
                                   syscall(SYS_newfstatat, AT_FDCWD, fixture,
                                           &st, 0) < 0 ? errno : 0));
                break;
            }
            case 'L': {
                char link[128];
                long r = syscall(SYS_readlinkat, AT_FDCWD, link_target, link,
                                 sizeof(link));

                pipe_write_u64(ready_pipe[1],
                               r < 0 ? (uint64_t)(uint32_t)errno : 0);
                break;
            }
            case 'P': {
                void *map = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
                                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
                long r;

                if (map == MAP_FAILED) {
                    pipe_write_u64(ready_pipe[1],
                                   (uint64_t)(uint32_t)errno);
                    break;
                }
                r = syscall(SYS_mprotect, map, 4096, PROT_READ);
                munmap(map, 4096);
                pipe_write_u64(ready_pipe[1],
                               r < 0 ? (uint64_t)(uint32_t)errno : 0);
                break;
            }
            default:
                running = 0;
                break;
            }
        }
        _exit(0);
    }
    close(ready_pipe[1]);
    close(ctl_pipe[0]);
    track_child(pid);
    pipe_getc(ready_pipe[0]);
    return pid;
}

/* Discard every queued syscall event so subtests start from a clean ring. */
static void drain_events(void)
{
    static struct ygg_syscall_event_record discard;
    int spins;

    for (spins = 0; spins < 128; spins++) {
        if (syscall_read_event(&discard, NULL) < 0)
            return;
    }
}

static void test_syscall_monitor(void)
{
    char fixture[128];
    int rpipe[2];
    int cpipe[2];
    pid_t pid;
    struct ygg_syscall_rule_request req;
    struct ygg_syscall_event_record record;
    uint64_t answer;

    g_section = "syscall";
    snprintf(fixture, sizeof(fixture), "%ssyscall_%d.bin", TEST_TMP_BASE,
             (int)getpid());
    CHECK(write_fixture(fixture, "1234567890abcdef", 16) == 0,
          "syscall fixture file");
    {
        char link_path[160];

        snprintf(link_path, sizeof(link_path), "%s.sym", fixture);
        unlink(link_path);
        CHECK(syscall(SYS_symlinkat, "ygg_link_target", AT_FDCWD,
                      link_path) == 0,
              "syscall fixture symlink");
    }

    pid = spawn_syscall_child(fixture, rpipe, cpipe);
    CHECK(pid > 0, "syscall child running");
    if (pid <= 0)
        return;

    /* --- return-value rewrite: getpid -> 424242 --- */
    syscall_rule_init(&req);
    req.syscall_nr = 172;   /* getpid */
    req.flags = YGG_SYSCALL_F_TRACE_ENTER | YGG_SYSCALL_F_TRACE_EXIT |
                YGG_SYSCALL_F_REPLACE_RETURN;
    req.target_pid = (uint32_t)pid;
    req.return_value = 424242;
    req.user_cookie = 0x172;
    CHECK(syscall_rule_set(&req) == 0, "set getpid return rule");

    drain_events();
    pipe_putc(cpipe[1], 'G');
    pipe_read_u64(rpipe[0], &answer);
    CHECK_EQ(answer, 424242, "getpid rewritten to 424242");

    /* Both enter and exit events were queued; drain and validate. */
    {
        int seen_enter = 0;
        int seen_exit = 0;
        int cookie_ok = 0;
        int replaced_ok = 0;
        int tries;

        for (tries = 0; tries < 4; tries++) {
            if (syscall_read_event(&record, NULL) < 0)
                break;
            if (record.event.magic != YGG_SYSCALL_EVENT_MAGIC)
                continue;
            if (record.event.syscall_nr != 172)
                continue;
            if (record.event.user_cookie == 0x172)
                cookie_ok = 1;
            if (record.event.phase == YGG_SYSCALL_PHASE_ENTER)
                seen_enter = 1;
            if (record.event.phase == YGG_SYSCALL_PHASE_EXIT) {
                seen_exit = 1;
                if (record.event.return_after == 424242 &&
                    (record.event.event_flags &
                     YGG_SYSCALL_EVENT_F_RETURN_REPLACED))
                    replaced_ok = 1;
            }
        }
        CHECK(seen_enter, "enter event observed");
        CHECK(seen_exit, "exit event observed");
        CHECK(cookie_ok, "event carries the rule cookie");
        CHECK(replaced_ok, "exit event shows replaced return");
    }

    CHECK(syscall_clear_rules() == 0, "clear rules");

    /* --- arg rewrite: write(fd, buf, 8) with length forced to 4 --- */
    {
        syscall_rule_init(&req);
        req.syscall_nr = 64;    /* write */
        req.flags = YGG_SYSCALL_F_TRACE_ENTER | YGG_SYSCALL_F_TRACE_EXIT;
        req.target_pid = (uint32_t)pid;
        req.arg_mask = 1ULL << 2;
        req.arg_values[2] = 4;
        req.user_cookie = 0x64;
        CHECK(syscall_rule_set(&req) == 0, "set write arg rule");

        drain_events();
        pipe_putc(cpipe[1], 'W');
        pipe_read_u64(rpipe[0], &answer);
        CHECK_EQ(answer, 4, "write truncated to 4 bytes");

        {
            int seen = 0;
            int tries;

            for (tries = 0; tries < 8; tries++) {
                if (syscall_read_event(&record, NULL) < 0)
                    break;
                if (record.event.syscall_nr != 64)
                    continue;
                if (record.event.phase != YGG_SYSCALL_PHASE_ENTER)
                    continue;
                if (record.event.event_flags &
                    YGG_SYSCALL_EVENT_F_ARGS_REPLACED) {
                    CHECK_EQ(record.event.args_after[2], 4ULL,
                             "arg2 replaced to 4");
                    CHECK_EQ(record.event.args_before[2], 8ULL,
                             "arg2 before was 8 (request length)");
                    seen = 1;
                }
            }
            CHECK(seen, "write enter event with replaced arg");
        }
        CHECK(syscall_clear_rules() == 0, "clear rules after arg test");
    }

    /* --- pathname capture on openat (nr 56) --- */
    {
        struct ygg_syscall_capture_desc cap;

        syscall_rule_init(&req);
        req.syscall_nr = 56;    /* openat */
        req.flags = YGG_SYSCALL_F_TRACE_ENTER;
        req.target_pid = (uint32_t)pid;
        req.user_cookie = 0x56;
        memset(&cap, 0, sizeof(cap));
        cap.arg_index = 1;
        cap.phase_mask = YGG_SYSCALL_CAPTURE_PHASE_ENTER;
        cap.kind = YGG_SYSCALL_CAPTURE_CSTRING;
        /* The ABI rejects pointer_source 0: an explicit register-set choice
         * (original vs. post-replacement args) is mandatory per descriptor. */
        cap.pointer_source = YGG_SYSCALL_CAPTURE_POINTER_ORIGINAL;
        cap.length_source = YGG_SYSCALL_CAPTURE_LENGTH_FIXED;
        cap.max_length = YGG_SYSCALL_CAPTURE_BYTES_MAX;
        req.captures[req.capture_count++] = cap;
        req.capture_total_limit = YGG_SYSCALL_CAPTURE_PAYLOAD_MAX;
        CHECK(syscall_rule_set(&req) == 0, "set openat capture rule");

        drain_events();
        pipe_putc(cpipe[1], 'O');
        pipe_read_u64(rpipe[0], &answer);
        CHECK((long)answer >= 0, "child openat succeeded");

        {
            int seen = 0;
            int tries;
            const struct ygg_syscall_capture_tlv *tlv;

            for (tries = 0; tries < 8; tries++) {
                if (syscall_read_event(&record, NULL) < 0)
                    break;
                if (record.event.syscall_nr != 56 ||
                    record.event.phase != YGG_SYSCALL_PHASE_ENTER)
                    continue;
                if (!(record.event.event_flags &
                      YGG_SYSCALL_EVENT_F_HAS_CAPTURES))
                    continue;
                tlv = (const struct ygg_syscall_capture_tlv *)
                          ((uint8_t *)&record + record.event.header_size);
                CHECK_EQ(tlv->kind,
                         (uint32_t)YGG_SYSCALL_CAPTURE_CSTRING,
                         "capture kind cstring");
                CHECK_EQ(tlv->status, 0U, "capture status ok");
                CHECK(strstr((const char *)tlv + sizeof(*tlv),
                             "syscall_") != NULL,
                      "captured path names the fixture");
                seen = 1;
            }
            CHECK(seen, "openat event with capture");
        }
        CHECK(syscall_clear_rules() == 0, "clear rules after capture");
    }

    /* --- fault capture: openat with a bad pointer --- */
    {
        struct ygg_syscall_capture_desc cap;

        syscall_rule_init(&req);
        req.syscall_nr = 56;
        req.flags = YGG_SYSCALL_F_TRACE_ENTER;
        req.target_pid = (uint32_t)pid;
        memset(&cap, 0, sizeof(cap));
        cap.arg_index = 1;
        cap.phase_mask = YGG_SYSCALL_CAPTURE_PHASE_ENTER;
        cap.kind = YGG_SYSCALL_CAPTURE_CSTRING;
        /* The ABI rejects pointer_source 0: an explicit register-set choice
         * (original vs. post-replacement args) is mandatory per descriptor. */
        cap.pointer_source = YGG_SYSCALL_CAPTURE_POINTER_ORIGINAL;
        cap.length_source = YGG_SYSCALL_CAPTURE_LENGTH_FIXED;
        cap.max_length = YGG_SYSCALL_CAPTURE_BYTES_MAX;
        req.captures[req.capture_count++] = cap;
        req.capture_total_limit = YGG_SYSCALL_CAPTURE_PAYLOAD_MAX;
        CHECK(syscall_rule_set(&req) == 0, "set fault capture rule");

        /* 'B' command: child performs openat with pointer 1. */
        drain_events();
        pipe_putc(cpipe[1], 'B');
        pipe_read_u64(rpipe[0], &answer);
        CHECK_EQ(answer, (uint64_t)EFAULT, "child openat failed with EFAULT");
        {
            int seen = 0;
            int tries;
            const struct ygg_syscall_capture_tlv *tlv;

            for (tries = 0; tries < 8; tries++) {
                if (syscall_read_event(&record, NULL) < 0)
                    break;
                if (record.event.syscall_nr != 56 ||
                    record.event.phase != YGG_SYSCALL_PHASE_ENTER)
                    continue;
                tlv = (const struct ygg_syscall_capture_tlv *)
                          ((uint8_t *)&record + record.event.header_size);
                CHECK(tlv->status & YGG_SYSCALL_CAPTURE_STATUS_FAULT,
                      "capture status fault");
                CHECK_EQ(tlv->address, 1ULL, "fault address recorded");
                seen = 1;
            }
            CHECK(seen, "fault capture event");
        }
        CHECK(syscall_clear_rules() == 0, "clear rules after fault");
    }

    /*
     * --- symbolic argument formatting (no kernel involvement) ---
     *
     * These exercise the same renderer and schema table the CLI uses, since
     * both now come from yggdrasil_syscall_format.h. No module state is
     * touched: this is pure string formatting.
     */
    {
        CHECK(format_syscall_arg(ARG_DIRFD, (uint64_t)(int64_t)AT_FDCWD) ==
                  "AT_FDCWD",
              "format: dirfd AT_FDCWD");
        CHECK(format_syscall_arg(ARG_MODE, 0777) == "0777",
              "format: mode is octal");
        CHECK(format_syscall_arg(ARG_PROT, PROT_READ | PROT_WRITE) ==
                  "PROT_READ|PROT_WRITE",
              "format: prot flags joined");
        CHECK(format_syscall_arg(ARG_PROT, 0) == "PROT_NONE",
              "format: prot zero is PROT_NONE");
        CHECK(format_syscall_arg(ARG_ACCESS_MODE, R_OK | X_OK) == "R_OK|X_OK",
              "format: access mode flags");
        CHECK(format_syscall_arg(ARG_ACCESS_MODE, 0) == "F_OK",
              "format: access mode zero is F_OK");
        CHECK(format_syscall_arg(ARG_AT_REMOVEDIR, AT_REMOVEDIR) ==
                  "AT_REMOVEDIR",
              "format: unlinkat AT_REMOVEDIR");
        CHECK(format_syscall_arg(ARG_AT_STAT, AT_SYMLINK_NOFOLLOW) ==
                  "AT_SYMLINK_NOFOLLOW",
              "format: newfstatat flags");
        /* An unnamed bit must fall back to hex, never be silently dropped. */
        CHECK(format_syscall_arg(ARG_AT_STAT, 0x80000000ULL) == "0x80000000",
              "format: unknown flag bit falls back to hex");
        CHECK(format_syscall_arg(ARG_UINT, 4096) == "4096",
              "format: length is decimal");
        CHECK(format_syscall_arg(ARG_INT, (uint64_t)(int64_t)-1) == "-1",
              "format: signed length is negative");
    }

    /*
     * The schema table decides what --capture auto asks the kernel for, so
     * check the entries this change added (and the one it removed).
     */
    {
        struct ygg_syscall_rule_request areq;
        uint32_t both = YGG_SYSCALL_F_TRACE_ENTER | YGG_SYSCALL_F_TRACE_EXIT;

        memset(&areq, 0, sizeof(areq));
        CHECK_EQ(populate_auto_capture_descriptors(
                     79, both, YGG_SYSCALL_CAPTURE_PAYLOAD_MAX, &areq),
                 0, "schema: newfstatat has a schema");
        CHECK_EQ(areq.capture_count, 2U,
                 "schema: newfstatat captures path and statbuf");
        CHECK_EQ(areq.captures[0].arg_index, 1U, "schema: path is arg1");
        CHECK_EQ(areq.captures[0].kind,
                 (uint32_t)YGG_SYSCALL_CAPTURE_CSTRING,
                 "schema: path is a cstring");
        CHECK_EQ(areq.captures[1].arg_index, 2U, "schema: statbuf is arg2");
        CHECK_EQ(areq.captures[1].schema_id,
                 (uint32_t)YGG_SYSCALL_SCHEMA_STAT,
                 "schema: statbuf uses the new stat schema");
        CHECK_EQ(areq.captures[1].fixed_length, 128U,
                 "schema: statbuf is the 128-byte arm64 struct stat");
        CHECK_EQ(areq.captures[1].phase_mask,
                 (uint32_t)YGG_SYSCALL_CAPTURE_PHASE_EXIT,
                 "schema: statbuf is captured on exit");

        /* Phase gating: an enter-only rule must not ask for exit captures. */
        memset(&areq, 0, sizeof(areq));
        CHECK_EQ(populate_auto_capture_descriptors(
                     79, YGG_SYSCALL_F_TRACE_ENTER,
                     YGG_SYSCALL_CAPTURE_PAYLOAD_MAX, &areq),
                 0, "schema: newfstatat enter-only still has a capture");
        CHECK_EQ(areq.capture_count, 1U,
                 "schema: exit capture dropped without trace-exit");

        /* mprotect is scalar-only: nothing to capture, so no schema. */
        memset(&areq, 0, sizeof(areq));
        CHECK_EQ(populate_auto_capture_descriptors(
                     226, both, YGG_SYSCALL_CAPTURE_PAYLOAD_MAX, &areq),
                 -1, "schema: mprotect has no capture schema");

        /* openat2 lost its automatic argument parsing. */
        memset(&areq, 0, sizeof(areq));
        CHECK_EQ(populate_auto_capture_descriptors(
                     437, both, YGG_SYSCALL_CAPTURE_PAYLOAD_MAX, &areq),
                 -1, "schema: openat2 no longer auto-parses");
    }

    /* Full call lines; NULL pending means "no across-phase merge needed". */
    {
        struct ygg_syscall_event fev;
        std::vector<struct syscall_capture_view> nocaps;
        std::string line;

        memset(&fev, 0, sizeof(fev));
        fev.phase = YGG_SYSCALL_PHASE_ENTER;
        fev.call_id = 1;
        fev.syscall_nr = 34;    /* mkdirat */
        fev.args_after[0] = (uint64_t)(int64_t)AT_FDCWD;
        fev.args_after[1] = 0x7fc0123456ULL;
        fev.args_after[2] = 0777;
        CHECK(format_syscall_call_line(&fev, nocaps, NULL) ==
                  "mkdirat(dirfd=AT_FDCWD, pathname=0x7fc0123456, mode=0777)",
              "call: mkdirat all args named (path stays hex without capture)");

        fev.syscall_nr = 226;   /* mprotect: scalar-only signature */
        fev.args_after[0] = 0x7fc0100000ULL;
        fev.args_after[1] = 4096;
        fev.args_after[2] = PROT_READ | PROT_WRITE | PROT_EXEC;
        CHECK(format_syscall_call_line(&fev, nocaps, NULL) ==
                  "mprotect(addr=0x7fc0100000, len=4096,"
                  " prot=PROT_READ|PROT_WRITE|PROT_EXEC)",
              "call: mprotect formats all three args");

        fev.syscall_nr = 43;    /* statfs: arg0 only */
        fev.args_after[0] = 0x3333;
        CHECK(format_syscall_call_line(&fev, nocaps, NULL) ==
                  "statfs(pathname=0x3333)",
              "call: statfs names just arg0");

        fev.syscall_nr = 437;   /* openat2: name kept, no auto-parse */
        CHECK(format_syscall_call_line(&fev, nocaps, NULL).empty(),
              "call: openat2 deliberately has no signature");

        fev.syscall_nr = 57;    /* close: no signature at all */
        CHECK(format_syscall_call_line(&fev, nocaps, NULL).empty(),
              "call: unlisted syscall produces no line");
    }

    /*
     * --- end-to-end: the kernel really auto-captures for these syscalls ---
     *
     * A "call:" line is only useful if the payload it decodes actually
     * arrives, so capture the pathname bytes for each newly added syscall and
     * assert both the TLV metadata and the decoded value.
     */
    {
        struct {
            uint32_t nr;
            uint8_t arg_index;
            char cmd;
            const char *label;
        } probes[] = {
            { 34, 1, 'M', "mkdirat" },
            { 79, 1, 'S', "newfstatat" },
            { 78, 1, 'L', "readlinkat" },
        };
        size_t p;

        for (p = 0; p < sizeof(probes) / sizeof(probes[0]); p++) {
            /*
             * Build the rule through the very same helper --capture auto
             * uses. Hand-writing the descriptor here would pass even if the
             * schema table named the wrong argument, which is the bug this
             * test exists to catch.
             */
            syscall_rule_init(&req);
            req.syscall_nr = probes[p].nr;
            req.flags = YGG_SYSCALL_F_TRACE_ENTER;
            req.target_pid = (uint32_t)pid;
            req.user_cookie = probes[p].nr;
            CHECK_EQ(populate_auto_capture_descriptors(
                         req.syscall_nr, req.flags,
                         YGG_SYSCALL_CAPTURE_PAYLOAD_MAX, &req),
                     0, "capture: schema table yields descriptors");
            CHECK_EQ(req.capture_count, 1U,
                     "capture: enter-only rule has one descriptor");
            CHECK_EQ(req.captures[0].arg_index, probes[p].arg_index,
                     "capture: schema names the pathname argument");
            CHECK_EQ(req.captures[0].kind,
                     (uint32_t)YGG_SYSCALL_CAPTURE_CSTRING,
                     "capture: schema says cstring");
            CHECK(syscall_rule_set(&req) == 0,
                  "capture: rule installed");

            drain_events();
            pipe_putc(cpipe[1], probes[p].cmd);
            pipe_read_u64(rpipe[0], &answer);
            CHECK_EQ(answer, 0, "capture: child syscall succeeded");

            {
                int seen = 0;
                int tries;

                for (tries = 0; tries < 8; tries++) {
                    const struct ygg_syscall_capture_tlv *tlv;
                    std::string rendered;

                    if (syscall_read_event(&record, NULL) < 0)
                        break;
                    if (record.event.syscall_nr != probes[p].nr)
                        continue;
                    if (record.event.phase != YGG_SYSCALL_PHASE_ENTER)
                        continue;
                    if (!(record.event.event_flags &
                          YGG_SYSCALL_EVENT_F_HAS_CAPTURES))
                        continue;
                    tlv = (const struct ygg_syscall_capture_tlv *)
                              ((uint8_t *)&record + record.event.header_size);
                    CHECK_EQ(tlv->arg_index, probes[p].arg_index,
                             "capture: arg index in TLV");
                    CHECK_EQ(tlv->kind,
                             (uint32_t)YGG_SYSCALL_CAPTURE_CSTRING,
                             "capture: TLV kind is cstring");
                    CHECK_EQ(tlv->status, 0U, "capture: TLV status ok");
                    CHECK_EQ(tlv->phase,
                             (uint32_t)YGG_SYSCALL_CAPTURE_PHASE_ENTER,
                             "capture: TLV phase is enter");
                    CHECK((const char *)tlv + sizeof(*tlv) != NULL &&
                              strstr((const char *)tlv + sizeof(*tlv),
                                     "syscall_") != NULL,
                          "capture: payload holds the fixture path");

                    /*
                     * Feed the real payload back through the renderer: this is
                     * what turns a captured pointer into pathname="...".
                     */
                    {
                        struct syscall_capture_view view;
                        std::vector<struct syscall_capture_view> caps;
                        struct ygg_syscall_event fev;

                        memset(&view, 0, sizeof(view));
                        view.tlv = *tlv;
                        view.data = (const uint8_t *)tlv + sizeof(*tlv);
                        caps.push_back(view);
                        memset(&fev, 0, sizeof(fev));
                        fev.phase = YGG_SYSCALL_PHASE_ENTER;
                        fev.call_id = record.event.call_id;
                        fev.syscall_nr = probes[p].nr;
                        memcpy(fev.args_after, record.event.args_after,
                               sizeof(fev.args_after));
                        rendered = format_syscall_call_line(&fev, caps, NULL);
                        CHECK(rendered.find("pathname=\"") != std::string::npos,
                              "capture: renderer decodes pathname");
                        CHECK(rendered.find("syscall_") != std::string::npos,
                              "capture: decoded value names the fixture");
                    }
                    seen = 1;
                }
                CHECK(seen, "capture: event with payload observed");
            }
            CHECK(syscall_clear_rules() == 0, "capture: rules cleared");
        }
    }

    /*
     * --- end-to-end: path captured on enter, struct stat on exit ---
     *
     * newfstatat fills a struct stat, so one "call:" line needs the path from
     * the enter event and the statbuf from the exit event. This drives the
     * real --capture auto descriptors, keeps the kernel's own captured bytes,
     * and replays them through the renderer: if the offsets, the schema id or
     * the enter/exit correlation were wrong, the decoded line would not match
     * the file this test just created.
     */
    {
        syscall_pending_args pending;
        static uint8_t enter_payload[YGG_SYSCALL_CAPTURE_PAYLOAD_MAX];
        static uint8_t exit_payload[YGG_SYSCALL_CAPTURE_PAYLOAD_MAX];
        struct ygg_syscall_capture_tlv enter_tlv;
        struct ygg_syscall_capture_tlv exit_tlv;
        struct ygg_syscall_event enter_ev;
        struct ygg_syscall_event exit_ev;
        size_t enter_bytes = 0;
        size_t exit_bytes = 0;
        int tries;

        syscall_rule_init(&req);
        req.syscall_nr = 79;    /* newfstatat */
        req.flags = YGG_SYSCALL_F_TRACE_ENTER | YGG_SYSCALL_F_TRACE_EXIT;
        req.target_pid = (uint32_t)pid;
        req.user_cookie = 0x79;
        CHECK_EQ(populate_auto_capture_descriptors(
                     req.syscall_nr, req.flags,
                     YGG_SYSCALL_CAPTURE_PAYLOAD_MAX, &req),
                 0, "exit-capture: auto descriptors for newfstatat");
        CHECK_EQ(req.capture_count, 2U,
                 "exit-capture: path plus statbuf requested");
        CHECK(syscall_rule_set(&req) == 0,
              "exit-capture: newfstatat rule installed");

        drain_events();
        pipe_putc(cpipe[1], 'S');
        pipe_read_u64(rpipe[0], &answer);
        CHECK_EQ(answer, 0, "exit-capture: child newfstatat succeeded");

        memset(&enter_tlv, 0, sizeof(enter_tlv));
        memset(&exit_tlv, 0, sizeof(exit_tlv));
        memset(&enter_ev, 0, sizeof(enter_ev));
        memset(&exit_ev, 0, sizeof(exit_ev));
        for (tries = 0; tries < 12; tries++) {
            const struct ygg_syscall_capture_tlv *tlv;
            size_t payload;

            if (syscall_read_event(&record, NULL) < 0)
                break;
            if (record.event.syscall_nr != 79)
                continue;
            if (!(record.event.event_flags &
                  YGG_SYSCALL_EVENT_F_HAS_CAPTURES))
                continue;
            if (record.event.capture_count != 1)
                continue;   /* one capture per phase for this rule */
            tlv = (const struct ygg_syscall_capture_tlv *)
                      ((uint8_t *)&record + record.event.header_size);
            payload = tlv->captured_length;
            if (payload > YGG_SYSCALL_CAPTURE_PAYLOAD_MAX)
                continue;
            if (record.event.phase == YGG_SYSCALL_PHASE_ENTER) {
                enter_tlv = *tlv;
                enter_ev = record.event;
                memcpy(enter_payload, (const uint8_t *)tlv + sizeof(*tlv),
                       payload);
                enter_bytes = payload;
            } else if (record.event.phase == YGG_SYSCALL_PHASE_EXIT) {
                exit_tlv = *tlv;
                exit_ev = record.event;
                memcpy(exit_payload, (const uint8_t *)tlv + sizeof(*tlv),
                       payload);
                exit_bytes = payload;
            }
        }
        CHECK(enter_bytes > 0, "exit-capture: enter payload captured");
        CHECK(exit_bytes > 0, "exit-capture: exit payload captured");
        CHECK_EQ(enter_tlv.kind, (uint32_t)YGG_SYSCALL_CAPTURE_CSTRING,
                 "exit-capture: enter payload is the path");
        CHECK_EQ(exit_tlv.kind, (uint32_t)YGG_SYSCALL_CAPTURE_STRUCT,
                 "exit-capture: exit payload is a struct");
        CHECK_EQ(exit_tlv.schema_id, (uint32_t)YGG_SYSCALL_SCHEMA_STAT,
                 "exit-capture: exit payload is struct stat");
        CHECK_EQ(exit_bytes, 128U, "exit-capture: struct stat is 128 bytes");

        if (enter_bytes > 0 && exit_bytes > 0) {
            struct syscall_capture_view view;
            std::vector<struct syscall_capture_view> caps;
            std::string enter_line;
            std::string merged;

            /* Enter first: it stashes the path for the exit event to reuse. */
            memset(&view, 0, sizeof(view));
            view.tlv = enter_tlv;
            view.data = enter_payload;
            caps.push_back(view);
            enter_line = format_syscall_call_line(&enter_ev, caps, &pending);
            CHECK(enter_line.find("pathname=\"") != std::string::npos,
                  "exit-capture: enter line decodes the path");

            caps.clear();
            memset(&view, 0, sizeof(view));
            view.tlv = exit_tlv;
            view.data = exit_payload;
            caps.push_back(view);
            merged = format_syscall_call_line(&exit_ev, caps, &pending);
            printf("     newfstatat exit line: %s\n", merged.c_str());

            CHECK(merged.find("pathname=\"") != std::string::npos,
                  "exit-capture: exit line carries the enter path");
            CHECK(merged.find("statbuf={") != std::string::npos,
                  "exit-capture: statbuf decoded as struct stat, not hex");
            /* The fixture is a 0644 regular file of 16 bytes. */
            CHECK(merged.find("mode=0100644") != std::string::npos,
                  "exit-capture: statbuf mode matches the fixture file");
            CHECK(merged.find("size=16") != std::string::npos,
                  "exit-capture: statbuf size matches the fixture file");
            CHECK(pending.empty(),
                  "exit-capture: correlation store drained by the exit event");
        }
        CHECK(syscall_clear_rules() == 0, "exit-capture: rules cleared");
    }

    /* --- uid-scope rule: a child that drops to uid 2000 --- */
    {
        pipe_putc(cpipe[1], 'U');
        pipe_read_u64(rpipe[0], &answer);
        CHECK_EQ(answer, 0, "child setuid(2000) ok");

        syscall_rule_init(&req);
        req.syscall_nr = 172;
        req.flags = YGG_SYSCALL_F_TRACE_ENTER | YGG_SYSCALL_F_REPLACE_RETURN |
                    YGG_SYSCALL_F_TARGET_UID;
        req.target_uid = 2000;
        req.return_value = 525252;
        req.user_cookie = 0x525;
        CHECK(syscall_rule_set(&req) == 0, "set uid-scoped rule");

        drain_events();
        pipe_putc(cpipe[1], 'G');
        pipe_read_u64(rpipe[0], &answer);
        CHECK_EQ(answer, 525252, "uid-scoped getpid rewritten");

        {
            int seen = 0;
            int tries;

            for (tries = 0; tries < 8; tries++) {
                if (syscall_read_event(&record, NULL) < 0)
                    break;
                if (record.event.syscall_nr != 172)
                    continue;
                if ((record.event.rule_flags &
                     YGG_SYSCALL_F_TARGET_UID) &&
                    record.event.target_uid == 2000)
                    seen = 1;
            }
            CHECK(seen, "event reports uid scope");
        }
        CHECK(syscall_clear_rules() == 0, "clear rules after uid test");
    }

    /* --- validation: ABI rejection and bad flags --- */
    {
        struct {
            uint32_t size;
            uint32_t version;
        } bad = {};

        bad.size = YGG_SYSCALL_RULE_REQUEST_SIZE - 8;
        bad.version = YGG_SYSCALL_ABI_VERSION;
        PRCTL_ERR("reject wrong size", EPROTO,
                  PR_YGGDRASIL_SYSCALL_CONFIG, &bad, 0, 0, 0);
        bad.size = YGG_SYSCALL_RULE_REQUEST_SIZE;
        bad.version = YGG_SYSCALL_ABI_VERSION + 1;
        PRCTL_ERR("reject wrong version", EPROTO,
                  PR_YGGDRASIL_SYSCALL_CONFIG, &bad, 0, 0, 0);

        syscall_rule_init(&req);
        req.syscall_nr = 172;
        req.flags = 0x40000;   /* unknown flag */
        PRCTL_ERR("reject unknown flag", EINVAL,
                  PR_YGGDRASIL_SYSCALL_CONFIG, &req, 0, 0, 0);
    }

    /* --- single-thread flag needs a tid --- */
    {
        syscall_rule_init(&req);
        req.syscall_nr = 172;
        req.flags = YGG_SYSCALL_F_SINGLE_THREAD | YGG_SYSCALL_F_TRACE_ENTER;
        req.target_pid = 0;
        PRCTL_ERR("reject single-thread without pid", EINVAL,
                  PR_YGGDRASIL_SYSCALL_CONFIG, &req, 0, 0, 0);
    }

    pipe_putc(cpipe[1], 'Q');
    waitpid(pid, NULL, 0);
    {
        char link_path[160];

        snprintf(link_path, sizeof(link_path), "%s.sym", fixture);
        unlink(link_path);
    }
    unlink(fixture);
}

/* ------------------------------------------------------------------ */
/* Section: stramp (user trampolines for syscall interception)          */
/* ------------------------------------------------------------------ */

/*
 * Trampoline contract: the kernel parks the task at on_enter/on_leave
 * with the syscall's x0-x5 (or, on leave, the return in x0) and x8 = nr.
 * The trampoline adjusts registers and re-enters with:
 *
 *     mov x6, <action>   ; 0 = run original with x0-x5, 1 = return x0
 *     mov x7, <magic>    ; YGG_STRAMP_MAGIC
 *     mov x8, <nr>       ; arm64 dispatches on x8 and the handshake
 *                         ; matches it against the parked flight
 *     svc #0             ; pc lands on the pad registered with the rule
 *
 * The shared page drives one-shot behavior: the parent arms `action` and
 * an optional argument rewrite before each command; the trampoline
 * consumes it (clears the armed flag) so the same code serves every
 * subtest. With on_leave registered one armed command passes through
 * BOTH trampolines, so each phase gets its own flag: `armed` for the
 * enter side, `leave_armed` for the leave side. When nothing is armed
 * the enter trampoline passes the syscall through unchanged (action 0,
 * original registers).
 */
struct stramp_shared {
    volatile unsigned long armed;      /* parent sets 1 before a command */
    volatile unsigned long leave_armed; /* consumed by the leave phase */
    volatile unsigned long action;     /* YGG_STRAMP_ACTION_* for enter */
    volatile unsigned long rewrite_len; /* 0 = keep x2, else replace x2 */
    volatile unsigned long leave_ret;  /* 0 = keep x0, else replace x0 */
    volatile unsigned long saw_args[6]; /* observed x0-x5 at enter */
    volatile unsigned long saw_ret;      /* observed x0 at leave */
};

static volatile struct stramp_shared *g_stramp;

#define STRAMP_OBSERVED_MAGIC 0x515452414D50ULL

/*
 * on_enter trampoline. Observed args are published to the shared page,
 * then the (possibly rewritten) registers are handed back with svc #0.
 * The label after the svc is the landing pad the rule registers.
 */
__attribute__((noinline)) static void stramp_enter_trampoline(void)
{
    unsigned long action = YGG_STRAMP_ACTION_CALL;
    unsigned long rewrite = 0;
    unsigned long magic = (unsigned long)YGG_STRAMP_MAGIC;
    unsigned long x0, x1, x2, x3, x4, x5;
    unsigned long nr;

    /*
     * x8 must survive to the re-entry svc: arm64 dispatches on regs->regs[8],
     * and the kernel-side handshake compares the re-entered nr against the
     * parked flight. The compiler treats x8 as ordinary scratch, so it is
     * saved here and restored in the re-entry block below.
     */
    __asm__ __volatile__(
        "mov %0, x0\n"
        "mov %1, x1\n"
        "mov %2, x2\n"
        "mov %3, x3\n"
        "mov %4, x4\n"
        "mov %5, x5\n"
        "mov %6, x8\n"
        : "=r"(x0), "=r"(x1), "=r"(x2), "=r"(x3), "=r"(x4), "=r"(x5),
          "=r"(nr)
        :
        : "x0", "x1", "x2", "x3", "x4", "x5", "x8");

    g_stramp->saw_args[0] = x0;
    g_stramp->saw_args[1] = x1;
    g_stramp->saw_args[2] = x2;
    g_stramp->saw_args[3] = x3;
    g_stramp->saw_args[4] = x4;
    g_stramp->saw_args[5] = x5;

    if (g_stramp->armed) {
        action = g_stramp->action;
        rewrite = g_stramp->rewrite_len;
        g_stramp->armed = 0;
    }
    if (rewrite) {
        /* The test harness routes its synthesized values through the
         * rewrite slot; for action RETURN it becomes the return value. */
        if (action == YGG_STRAMP_ACTION_RETURN)
            x0 = rewrite;
        else
            x2 = rewrite;
    }

    __asm__ __volatile__(
        "mov x0, %0\n"
        "mov x1, %1\n"
        "mov x2, %2\n"
        "mov x3, %3\n"
        "mov x4, %4\n"
        "mov x5, %5\n"
        "mov x6, %6\n"
        "mov x7, %7\n"
        "mov x8, %8\n"
        "svc #0\n"
        ".globl stramp_enter_pad\n"
        "stramp_enter_pad:\n"
        : : "r"(x0), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5),
          "r"(action), "r"(magic), "r"(nr)
        : "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "memory");
}

/*
 * on_leave trampoline: observes the original return in x0 and replaces
 * it when the shared page asks, then hands the final value back.
 */
__attribute__((noinline)) static void stramp_leave_trampoline(void)
{
    unsigned long replace = 0;
    unsigned long magic = (unsigned long)YGG_STRAMP_MAGIC;
    unsigned long x0;
    unsigned long nr;

    /* x8 carries the intercepted nr from the after-hook park; it must be
     * re-supplied at the re-entry svc for dispatch and the T4 handshake. */
    __asm__ __volatile__("mov %0, x0\n" "mov %1, x8\n"
                         : "=r"(x0), "=r"(nr)
                         :
                         : "x0", "x8");

    g_stramp->saw_ret = x0;

    if (g_stramp->leave_armed) {
        replace = g_stramp->leave_ret;
        g_stramp->leave_armed = 0;
    }
    if (replace)
        x0 = replace;

    __asm__ __volatile__(
        "mov x0, %0\n"
        "mov x7, %1\n"
        "mov x8, %2\n"
        "svc #0\n"
        ".globl stramp_leave_pad\n"
        "stramp_leave_pad:\n"
        : : "r"(x0), "r"(magic), "r"(nr)
        : "x0", "x7", "x8", "memory");
}

static long stramp_config(struct ygg_stramp_request *req)
{
    errno = 0;
    return prctl(PR_YGGDRASIL_SYSCALL_TRAMPOLINE, req, 0, 0, 0);
}

static void stramp_rule_init(struct ygg_stramp_request *req, uint32_t op)
{
    memset(req, 0, sizeof(*req));
    req->size = YGG_STRAMP_REQUEST_SIZE;
    req->version = YGG_STRAMP_ABI_VERSION;
    req->op = op;
}

static long stramp_clear_rules(void)
{
    struct ygg_stramp_request req;

    stramp_rule_init(&req, YGG_STRAMP_RULE_CLEAR);
    req.syscall_nr = YGG_SYSCALL_NR_ALL;
    return stramp_config(&req);
}

/*
 * Landing pads: the asm blocks above emit these labels right after
 * their svc #0, so taking their address yields the exact pc the kernel
 * observes at re-entry. The labels are emitted with .globl inside the
 * functions; the externs below resolve to them at link time.
 */
extern char stramp_enter_pad[];
extern char stramp_leave_pad[];

/*
 * Raw svc without bionic's errno mapping: syscall() folds every kernel
 * return in [-4095,-1] to -1 plus errno, which would hide exactly the
 * negative values the clamp tests must observe. The trampoline ABI is
 * defined on the kernel's raw x0, so read it directly.
 *
 * noinline is mandatory, not cosmetic: when a stramp rule intercepts this
 * syscall the kernel parks the task inside the user trampolines, and the
 * value the caller finally sees returns only after their code ran. The
 * trampolines are free to trash every caller-saved register (the leave
 * trampoline happens to leave the syscall number in x9), so a C caller
 * must not keep live values in caller-saved registers across the svc the
 * way a plain syscall would allow. A real call boundary forces the
 * compiler to spill or reload them; an inlined svc does not.
 */
__attribute__((noinline)) static long raw_svc_call3(long nr, long a0,
                                                   long a1, long a2)
{
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a0;
    register long x1 __asm__("x1") = a1;
    register long x2 __asm__("x2") = a2;

    __asm__ __volatile__("svc #0"
                         : "+r"(x0)
                         : "r"(x8), "r"(x1), "r"(x2)
                         : "memory");
    return x0;
}

static pid_t spawn_stramp_child(volatile struct stramp_shared **shared_out,
                                int ready_pipe[2], int ctl_pipe[2])
{
    volatile struct stramp_shared *shared;
    pid_t pid;

    if (pipe(ready_pipe) < 0 || pipe(ctl_pipe) < 0)
        return -1;

    shared = (volatile struct stramp_shared *)mmap(
        NULL, sizeof(*shared), PROT_READ | PROT_WRITE,
        MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (shared == MAP_FAILED)
        return -1;
    shared->armed = 0;
    shared->leave_armed = 0;
    shared->action = YGG_STRAMP_ACTION_CALL;
    shared->rewrite_len = 0;
    shared->leave_ret = 0;
    shared->saw_ret = 0;

    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int sink = open("/dev/null", O_WRONLY | O_CLOEXEC);
        int running = 1;

        close(ready_pipe[0]);
        close(ctl_pipe[1]);
        g_stramp = shared;
        pipe_putc(ready_pipe[1], 'R');
        while (running) {
            int c = pipe_getc(ctl_pipe[0]);

            switch (c) {
            case 'G':
                /* getpid through the trampoline path. Raw svc, not
                 * bionic syscall(): the wrapper folds kernel returns in
                 * [-4095,-1] to -1+errno, hiding exactly the negative
                 * values the clamp checks must observe. */
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)raw_svc_call3(SYS_getpid, 0,
                                                       0, 0));
                break;
            case 'W': {
                static const char msg[] = "ARGUMENT";  /* 8 bytes */

                errno = 0;
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)syscall(SYS_write, sink, msg,
                                                 sizeof(msg) - 1));
                break;
            }
            case 'B': {
                static const char msg[] = "BADFD";

                /* write to a bad fd: the origin's -EBADF must reach the
                 * parent unchanged through the trampoline path. */
                errno = 0;
                pipe_write_u64(ready_pipe[1], (uint64_t)raw_svc_call3(
                    SYS_write, -1, (long)msg, (long)sizeof(msg) - 1));
                break;
            }
            default:
                running = 0;
                break;
            }
        }
        _exit(0);
    }
    close(ready_pipe[1]);
    close(ctl_pipe[0]);
    track_child(pid);
    *shared_out = shared;
    pipe_getc(ready_pipe[0]);
    return pid;
}

static void stramp_arm(volatile struct stramp_shared *shared,
                       unsigned long action, unsigned long rewrite_len,
                       unsigned long leave_ret)
{
    shared->action = action;
    shared->rewrite_len = rewrite_len;
    shared->leave_ret = leave_ret;
    __sync_synchronize();
    /* One command may traverse both trampolines (enter then leave); each
     * phase consumes its own flag. */
    shared->leave_armed = 1;
    shared->armed = 1;
}

/*
 * Shared request slot for the uid-registrant child. The parent builds the
 * full ygg_stramp_request here; the child reads it under the ctl_pipe
 * handshake and issues the prctl itself, so authorization sees the
 * child's (dropped) credentials.
 */
struct stramp_uid_shared {
    struct ygg_stramp_request req;
    volatile struct stramp_shared stramp;
};

static void stramp_uid_write_req(volatile struct stramp_uid_shared *shared,
                                 const struct ygg_stramp_request *req)
{
    memcpy((void *)&shared->req, req, sizeof(*req));
    __sync_synchronize();
}

/*
 * Non-root registrant: drops to uid 2000, then serves commands.
 *   'U' syscall(SYS_setuid, 2000)              -> reports errno (0 on ok)
 *   'R' prctl(SET, request from shared page)   -> reports raw prctl ret
 *   'D' prctl(DELETE, request from shared page)-> reports raw prctl ret
 *   'C' prctl(CLEAR)                           -> reports raw prctl ret
 *   'F' fork grandchild: raw_svc_call3(getpid) twice (enter trampoline
 *       runs via the uid rule), first value is the grandchild's pid
 *       (reported), second is the value the caller observed (reported)
 *   'Q' exits
 *
 * The grandchild inherits the MAP_SHARED page and g_stramp, so the same
 * stramp_enter_trampoline decides its getpid calls. It never runs the
 * registrant's code path: only fork + svc + write + _exit, which is the
 * point of the test.
 */
static pid_t spawn_stramp_uid_child(
    volatile struct stramp_uid_shared **shared_out,
    int ready_pipe[2], int ctl_pipe[2])
{
    volatile struct stramp_uid_shared *shared;
    pid_t pid;

    if (pipe(ready_pipe) < 0 || pipe(ctl_pipe) < 0)
        return -1;

    shared = (volatile struct stramp_uid_shared *)mmap(
        NULL, sizeof(*shared), PROT_READ | PROT_WRITE,
        MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (shared == MAP_FAILED)
        return -1;
    shared->stramp.armed = 0;
    shared->stramp.leave_armed = 0;
    shared->stramp.action = YGG_STRAMP_ACTION_CALL;
    shared->stramp.rewrite_len = 0;
    shared->stramp.leave_ret = 0;
    shared->stramp.saw_ret = 0;

    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int running = 1;

        close(ready_pipe[0]);
        close(ctl_pipe[1]);
        g_stramp = &shared->stramp;
        pipe_putc(ready_pipe[1], 'R');
        while (running) {
            int c = pipe_getc(ctl_pipe[0]);

            switch (c) {
            case 'U':
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)(uint32_t)(syscall(SYS_setuid, 2000)
                                                     ? errno : 0));
                break;
            case 'R':
            case 'D': {
                struct ygg_stramp_request local;
                long ret;

                memcpy(&local, (const void *)&shared->req, sizeof(local));
                errno = 0;
                ret = stramp_config(&local);
                pipe_write_u64(ready_pipe[1],
                                (uint64_t)(unsigned long)(ret < 0 ?
                                    (errno ? -(long)errno : ret) : 0));
                break;
            }
            case 'C': {
                long ret = stramp_clear_rules();

                pipe_write_u64(ready_pipe[1],
                               (uint64_t)(unsigned long)(ret < 0 ?
                                   -(long)errno : 0));
                break;
            }
            case 'F': {
                pid_t g = fork();
                uint64_t observed;

                if (g == 0) {
                    /* Grandchild: one getpid through the trampoline path
                     * (uid rule -> enter trampoline -> REPLACE action),
                     * reported by the registrant below. */
                    observed = (uint64_t)raw_svc_call3(SYS_getpid, 0, 0, 0);
                    pipe_write_u64(ready_pipe[1], observed);
                    _exit(0);
                }
                pipe_write_u64(ready_pipe[1], (uint64_t)g);
                waitpid(g, NULL, 0);
                break;
            }
            default:
                running = 0;
                break;
            }
        }
        _exit(0);
    }
    close(ready_pipe[1]);
    close(ctl_pipe[0]);
    track_child(pid);
    *shared_out = shared;
    pipe_getc(ready_pipe[0]);
    return pid;
}

static void test_stramp(void)
{
    volatile struct stramp_shared *shared = NULL;
    struct ygg_stramp_request req;
    int ready_pipe[2];
    int ctl_pipe[2];
    uint64_t val = 0;
    pid_t pid;
    pid_t child_pid;

    stramp_clear_rules();
    g_section = "stramp";

    pid = spawn_stramp_child(&shared, ready_pipe, ctl_pipe);
    CHECK(pid > 0, "spawn stramp child");
    if (pid <= 0)
        return;
    child_pid = pid;

    /* Baseline: no rules - plain syscalls. */
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 && val == (uint64_t)pid,
          "plain getpid without rules");

    /* --- attach mode: rewrite a write() length argument --- */
    stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
    req.syscall_nr = 64;    /* write */
    req.mode = YGG_STRAMP_MODE_ATTACH;
    req.target_pid = (uint32_t)child_pid;
    req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
    req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
    PRCTL_OK("set write attach rule", PR_YGGDRASIL_SYSCALL_TRAMPOLINE,
             &req, 0, 0, 0);

    stramp_arm(shared, YGG_STRAMP_ACTION_CALL, 4, 0);
    pipe_putc(ctl_pipe[1], 'W');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 && val == 4,
          "attach rewrites write length 8 to 4");
    CHECK(shared->saw_args[2] == 8,
          "enter trampoline observed original length");

    /* Armed flag is consumed; an unarmed pass-through must not rewrite. */
    shared->rewrite_len = 4;
    pipe_putc(ctl_pipe[1], 'W');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 && val == 8,
          "unarmed trampoline passes arguments through");
    shared->rewrite_len = 0;

    /* --- attach mode: rewrite the return value via on_leave --- */
    stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
    req.syscall_nr = 172;   /* getpid */
    req.mode = YGG_STRAMP_MODE_ATTACH;
    req.target_pid = (uint32_t)child_pid;
    req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
    req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
    req.on_leave_addr = (uint64_t)(uintptr_t)stramp_leave_trampoline;
    req.leave_pad = (uint64_t)(uintptr_t)stramp_leave_pad;
    PRCTL_OK("set getpid attach rule with on_leave",
             PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &req, 0, 0, 0);

    stramp_arm(shared, YGG_STRAMP_ACTION_CALL, 0, 424242);
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 && val == 424242,
          "on_leave rewrites getpid return to 424242");
    CHECK(shared->saw_ret == (unsigned long)child_pid,
          "leave trampoline observed original return");

    /* --- mode enforcement: attach ignores action RETURN --- */
    stramp_arm(shared, YGG_STRAMP_ACTION_RETURN, 0, 0);
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 &&
          val == (uint64_t)child_pid,
          "attach mode ignores action RETURN (origin still runs)");

    /*
     * --- return-value clamping: errno must survive, ERESTART must not ---
     *
     * Every trampoline-facing return value passes through the kernel-side
     * clamp, whose only job is to stop [512,516] (ERESTART*) values from
     * rewinding pc onto trampoline-internal code. A buggy clamp that maps
     * all negative values to -EINTR silently rewrites every failure the
     * caller observes, so pin both edges down here.
     *
     * The write rule currently in force is enter-only; re-arm it with the
     * leave pair so the origin's real errno traverses T3/T4 on the 'B'
     * (bad-fd write) command.
     */
    stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
    req.syscall_nr = 64;    /* write */
    req.mode = YGG_STRAMP_MODE_ATTACH;
    req.target_pid = (uint32_t)child_pid;
    req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
    req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
    req.on_leave_addr = (uint64_t)(uintptr_t)stramp_leave_trampoline;
    req.leave_pad = (uint64_t)(uintptr_t)stramp_leave_pad;
    PRCTL_OK("set write attach rule with on_leave",
             PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &req, 0, 0, 0);

    /* Attach + on_leave pass-through: a genuinely failing write() keeps
     * its -EBADF instead of degrading to -EINTR. */
    stramp_arm(shared, YGG_STRAMP_ACTION_CALL, 0, 0);
    pipe_putc(ctl_pipe[1], 'B');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 &&
          val == (uint64_t)-EBADF,
          "attach on_leave passes real errno through unchanged");
    CHECK(shared->saw_ret == (unsigned long)-EBADF,
          "leave trampoline observed the real -EBADF return");

    /* Attach + on_leave replacement with a negative value (getpid rule). */
    stramp_arm(shared, YGG_STRAMP_ACTION_CALL, 0, (unsigned long)-2);
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 &&
          val == (uint64_t)-2,
          "attach on_leave replacement of -ENOENT survives");

    /* Attach + on_leave replacement with ERESTARTSYS: the kernel must
     * clamp it to -EINTR, because the exit path would otherwise rewind
     * pc onto trampoline-internal code. */
    stramp_arm(shared, YGG_STRAMP_ACTION_CALL, 0, (unsigned long)-512);
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 &&
          val == (uint64_t)-EINTR,
          "attach on_leave ERESTARTSYS is clamped to -EINTR");

    /* --- replace mode: skip the origin entirely --- */
    stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
    req.syscall_nr = 172;   /* getpid */
    req.mode = YGG_STRAMP_MODE_REPLACE;
    req.target_pid = (uint32_t)child_pid;
    req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
    req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
    PRCTL_OK("set getpid replace rule", PR_YGGDRASIL_SYSCALL_TRAMPOLINE,
             &req, 0, 0, 0);

    stramp_arm(shared, YGG_STRAMP_ACTION_RETURN, 0, 0);
    shared->rewrite_len = 7777;   /* the trampoline routes this into x0 */
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 && val == 7777,
          "replace mode returns trampoline value without origin");
    CHECK(val != (uint64_t)child_pid,
          "replace mode did not run the original getpid");
    shared->rewrite_len = 0;

    /* Replace mode synthesizing negative returns: both a plain errno and
     * the ERESTART window, symmetric with the attach-mode checks above. */
    stramp_arm(shared, YGG_STRAMP_ACTION_RETURN, (unsigned long)-13, 0);
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 &&
          val == (uint64_t)-13,
          "replace mode RETURN of -EACCES survives");
    stramp_arm(shared, YGG_STRAMP_ACTION_RETURN, (unsigned long)-516, 0);
    pipe_putc(ctl_pipe[1], 'G');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 &&
          val == (uint64_t)-EINTR,
          "replace mode RETURN of ERESTART_RESTARTBLOCK is clamped");

    /* --- replace mode: modified arguments still calling the origin --- */
    stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
    req.syscall_nr = 64;    /* write */
    req.mode = YGG_STRAMP_MODE_REPLACE;
    req.target_pid = (uint32_t)child_pid;
    req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
    req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
    PRCTL_OK("set write replace rule", PR_YGGDRASIL_SYSCALL_TRAMPOLINE,
             &req, 0, 0, 0);

    stramp_arm(shared, YGG_STRAMP_ACTION_CALL, 4, 0);
    pipe_putc(ctl_pipe[1], 'W');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 && val == 4,
          "replace mode with action CALL runs origin with new args");

    /* --- deletion stops interception --- */
    stramp_rule_init(&req, YGG_STRAMP_RULE_DELETE);
    req.syscall_nr = 64;
    req.target_pid = (uint32_t)child_pid;
    PRCTL_OK("delete write rule", PR_YGGDRASIL_SYSCALL_TRAMPOLINE,
             &req, 0, 0, 0);
    pipe_putc(ctl_pipe[1], 'W');
    CHECK(pipe_read_u64(ready_pipe[0], &val) == 0 && val == 8,
          "deleted rule restores plain write");

    /*
     * --- uid rules from a non-root registrant ---
     *
     * A registrant drops to uid 2000 and registers rules for its own uid
     * (allowed), while rules for other uids, global scope, and CLEAR stay
     * root-only. The same uid rule must reach a forked grandchild: real uid
     * is inherited across fork, so T1 matching covers the child even though
     * it never runs the registering code. That is the machine-code scheme's
     * core claim - verify it with the kernel-side trampolines here.
     */
    {
        volatile struct stramp_uid_shared *uid_shared = NULL;
        int uid_ready[2], uid_ctl[2];
        pid_t registrant;
        uint64_t val = 0;
        pid_t grandchild;

        registrant = spawn_stramp_uid_child(&uid_shared, uid_ready,
                                            uid_ctl);
        CHECK(registrant > 0, "spawn non-root stramp registrant");
        if (registrant > 0) {
            /* Authorization matrix, all issued by the uid-2000 child. */
            pipe_putc(uid_ctl[1], 'U');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 && val == 0,
                  "registrant setuid(2000) ok");

            /* uid == self: allowed (new behavior). */
            stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
            req.syscall_nr = 172;   /* getpid */
            req.mode = YGG_STRAMP_MODE_REPLACE;
            req.flags = YGG_STRAMP_F_TARGET_UID;
            req.target_uid = 2000;
            req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
            req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
            stramp_uid_write_req(uid_shared, &req);
            pipe_putc(uid_ctl[1], 'R');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 && val == 0,
                  "non-root set uid==self rule ok");

            /* uid == root's or a stranger's: refused. */
            req.target_uid = 0;
            stramp_uid_write_req(uid_shared, &req);
            pipe_putc(uid_ctl[1], 'R');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 &&
                  val == (uint64_t)(unsigned long)-EPERM,
                  "non-root uid rule for root refused");
            req.target_uid = 12345;
            stramp_uid_write_req(uid_shared, &req);
            pipe_putc(uid_ctl[1], 'R');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 &&
                  val == (uint64_t)(unsigned long)-EPERM,
                  "non-root uid rule for another uid refused");

            /* Global scope and CLEAR stay root-only. */
            stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
            req.syscall_nr = 172;
            req.mode = YGG_STRAMP_MODE_REPLACE;
            req.target_pid = 0;
            req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
            req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
            stramp_uid_write_req(uid_shared, &req);
            pipe_putc(uid_ctl[1], 'R');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 &&
                  val == (uint64_t)(unsigned long)-EPERM,
                  "non-root global rule refused");
            pipe_putc(uid_ctl[1], 'C');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 &&
                  val == (uint64_t)(unsigned long)-EPERM,
                  "non-root CLEAR refused");

            /* Register the working rule again (the last 'R' overwrote it). */
            stramp_rule_init(&req, YGG_STRAMP_RULE_SET);
            req.syscall_nr = 172;
            req.mode = YGG_STRAMP_MODE_REPLACE;
            req.flags = YGG_STRAMP_F_TARGET_UID;
            req.target_uid = 2000;
            req.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
            req.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
            stramp_uid_write_req(uid_shared, &req);
            pipe_putc(uid_ctl[1], 'R');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 && val == 0,
                  "non-root registers working uid rule");

            /* Grandchild (same uid via fork) hits the rule: uid scope
             * covers forked descendants. */
            stramp_arm(&uid_shared->stramp, YGG_STRAMP_ACTION_RETURN,
                       313131, 0);
            pipe_putc(uid_ctl[1], 'F');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 && val > 0,
                  "grandchild forked under uid rule");
            grandchild = (pid_t)val;
            track_child(grandchild);
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 && val == 313131,
                  "grandchild getpid rewritten by uid rule");
            kill(grandchild, SIGKILL);
            waitpid(grandchild, NULL, 0);

            /* Grandchild getpid report must equal its real pid when the
             * rule is gone: delete from the non-root registrant, fork
             * again, observe the plain value. */
            stramp_rule_init(&req, YGG_STRAMP_RULE_DELETE);
            req.syscall_nr = 172;
            req.flags = YGG_STRAMP_F_TARGET_UID;
            req.target_uid = 2000;
            stramp_uid_write_req(uid_shared, &req);
            pipe_putc(uid_ctl[1], 'D');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 && val == 0,
                  "non-root deletes own uid rule");
            pipe_putc(uid_ctl[1], 'F');
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 && val > 0,
                  "second grandchild forked after delete");
            grandchild = (pid_t)val;
            track_child(grandchild);
            CHECK(pipe_read_u64(uid_ready[0], &val) == 0 &&
                  val == (uint64_t)grandchild,
                  "grandchild getpid plain after delete");
            kill(grandchild, SIGKILL);
            waitpid(grandchild, NULL, 0);

            pipe_putc(uid_ctl[1], 'Q');
            waitpid(registrant, NULL, 0);
            munmap((void *)uid_shared, sizeof(*uid_shared));
        }
    }

    /* --- ABI validation --- */
    {
        struct ygg_stramp_request bad;

        stramp_rule_init(&bad, YGG_STRAMP_RULE_SET);
        bad.syscall_nr = 172;
        bad.mode = YGG_STRAMP_MODE_ATTACH;
        bad.target_pid = (uint32_t)child_pid;
        bad.on_enter_addr = (uint64_t)(uintptr_t)stramp_enter_trampoline;
        bad.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
        bad.size = YGG_STRAMP_REQUEST_SIZE - 1;
        PRCTL_ERR("reject wrong size", EPROTO,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
        bad.size = YGG_STRAMP_REQUEST_SIZE;
        bad.version = YGG_STRAMP_ABI_VERSION + 1;
        PRCTL_ERR("reject wrong version", EPROTO,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
        bad.version = YGG_STRAMP_ABI_VERSION;
        bad.flags = 0x40000;    /* unknown flag */
        PRCTL_ERR("reject unknown flag", EINVAL,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
        bad.flags = 0;
        bad.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad + 1;
        PRCTL_ERR("reject unaligned pad", EINVAL,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
        bad.enter_pad = (uint64_t)(uintptr_t)stramp_enter_pad;
        bad.on_leave_addr = (uint64_t)(uintptr_t)stramp_leave_trampoline;
        /* leave pad missing while on_leave present */
        bad.leave_pad = 0;
        PRCTL_ERR("reject on_leave without leave pad", EINVAL,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
        bad.leave_pad = (uint64_t)(uintptr_t)stramp_leave_pad;
        bad.mode = YGG_STRAMP_MODE_REPLACE;
        /* replace mode must not carry leave fields */
        PRCTL_ERR("reject replace mode with leave fields", EINVAL,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
        bad.mode = YGG_STRAMP_MODE_REPLACE;
        bad.on_leave_addr = 0;
        bad.leave_pad = 0;
        bad.syscall_nr = __NR_exit_group;
        PRCTL_ERR("reject exit_group", EOPNOTSUPP,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
        bad.syscall_nr = __NR_prctl;
        PRCTL_ERR("reject prctl", EOPNOTSUPP,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);

        stramp_rule_init(&bad, YGG_STRAMP_RULE_SET);
        bad.syscall_nr = 172;
        bad.mode = YGG_STRAMP_MODE_ATTACH;
        bad.target_pid = 0;
        PRCTL_ERR("reject single-thread without pid", EINVAL,
                  PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &bad, 0, 0, 0);
    }

    stramp_clear_rules();
    pipe_putc(ctl_pipe[1], 'Q');
    waitpid(pid, NULL, 0);
    munmap((void *)shared, sizeof(*shared));
}

/* ------------------------------------------------------------------ */
/* Section: freeze                                                    */
/* ------------------------------------------------------------------ */

#define FREEZE_WORKERS 4

/*
 * Shared page for the freeze section: workers bump `counter`, and each of
 * the FREEZE_WORKERS originals records its tid in `tids` at startup so the
 * parent can inspect a real worker (the child's main thread is exempt from
 * the freeze and would never show 'T').
 */
struct freeze_shared {
    volatile unsigned long counter;
    volatile int tids[FREEZE_WORKERS];
};

struct freeze_worker_args {
    struct freeze_shared *shared;
    int idx;   /* -1: extra worker ('T'/'C' probes), does not register */
};

static void *freeze_worker(void *arg)
{
    struct freeze_worker_args *args = (struct freeze_worker_args *)arg;

    if (args->idx >= 0)
        args->shared->tids[args->idx] = (int)syscall(SYS_gettid);
    for (;;) {
        args->shared->counter++;
        sched_yield();
    }
    return NULL;
}

/*
 * Freezes the group from a worker thread, reports the result, then exits:
 * the owner's death must auto-thaw the group (do_exit hook). pthread_exit
 * reaches the same do_exit as a normal thread return.
 */
static void *freeze_owner_exit_thread(void *arg)
{
    int ready_w = *(int *)arg;
    struct ygg_freeze_request req = {};

    req.enable = 1;
    pipe_write_u64(ready_w, (uint64_t)(long)prctl(PR_YGGDRASIL_FREEZE,
                                                  &req, 0, 0, 0));
    pthread_exit(NULL);
    return NULL;
}

static pid_t spawn_freeze_child(struct freeze_shared **shared_out,
                                int ready_pipe[2], int ctl_pipe[2])
{
    struct freeze_shared *shared;
    struct freeze_worker_args wargs[FREEZE_WORKERS];
    struct freeze_worker_args spare;
    int zargs;
    pthread_t workers[FREEZE_WORKERS];
    pid_t pid;
    int i;

    /* MAP_SHARED before fork: parent and child observe the same page. */
    shared = (struct freeze_shared *)mmap(
        NULL, sizeof(*shared), PROT_READ | PROT_WRITE,
        MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (shared == MAP_FAILED)
        return -1;
    *shared_out = shared;

    if (pipe(ready_pipe) < 0 || pipe(ctl_pipe) < 0)
        return -1;
    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int running = 1;

        close(ready_pipe[0]);
        close(ctl_pipe[1]);
        spare.shared = shared;
        spare.idx = -1;
        zargs = ready_pipe[1];
        for (i = 0; i < FREEZE_WORKERS; i++) {
            wargs[i].shared = shared;
            wargs[i].idx = i;
            if (pthread_create(&workers[i], NULL, freeze_worker,
                               &wargs[i]) != 0)
                _exit(3);
        }
        pipe_putc(ready_pipe[1], 'R');
        while (running) {
            int c = pipe_getc(ctl_pipe[0]);

            switch (c) {
            case 'F': {
                struct ygg_freeze_request req = {};

                req.enable = 1;
                pipe_write_u64(ready_pipe[1],
                               (uint64_t)(long)prctl(PR_YGGDRASIL_FREEZE,
                                                     &req, 0, 0, 0));
                break;
            }
            case 'T':
            case 'C': {
                pthread_t t;
                int err = pthread_create(&t, NULL, freeze_worker, &spare);

                /* Workers never exit; detach so a created one stays clean. */
                if (!err)
                    pthread_detach(t);
                pipe_write_u64(ready_pipe[1], (uint64_t)err);
                break;
            }
            case 'R': {
                struct ygg_freeze_request req = {};

                pipe_write_u64(ready_pipe[1],
                               (uint64_t)(long)prctl(PR_YGGDRASIL_FREEZE,
                                                     &req, 0, 0, 0));
                break;
            }
            case 'Z': {
                pthread_t t;

                /* The freezer thread dies right after freezing: the module
                 * must auto-thaw or the whole child stays stopped forever. */
                if (pthread_create(&t, NULL, freeze_owner_exit_thread,
                                   &zargs) == 0)
                    pthread_detach(t);
                break;
            }
            default:
                running = 0;
                break;
            }
        }
        _exit(0);
    }
    close(ready_pipe[1]);
    close(ctl_pipe[0]);
    track_child(pid);
    pipe_getc(ready_pipe[0]);
    return pid;
}

/* Wait until the shared counter has advanced between two samples. */
static int counter_advances(volatile unsigned long *counter)
{
    unsigned long a = *counter;
    int i;

    for (i = 0; i < 40; i++) {
        usleep(50000);
        if (*counter != a)
            return 1;
    }
    return 0;
}/* Wait until the shared counter stays put across two samples. */
static int counter_frozen(volatile unsigned long *counter)
{
    unsigned long a;
    int i;

    usleep(200000);   /* let stragglers reach their stop point */
    a = *counter;
    usleep(200000);
    return *counter == a;
}

static void freeze_child_req(struct ygg_freeze_request *req, uint32_t enable,
                             uint32_t flags, uint32_t pid)
{
    memset(req, 0, sizeof(*req));
    req->enable = enable;
    req->flags = flags;
    req->pid = pid;
}

static void test_freeze(void)
{
    struct ygg_freeze_request req;
    struct freeze_shared *shared;
    int rpipe[2];
    int cpipe[2];
    pid_t pid;
    uint64_t answer;
    pid_t worker_tid = 0;
    char state[32];
    int spin;

    g_section = "freeze";
    pid = spawn_freeze_child(&shared, rpipe, cpipe);
    CHECK(pid > 0, "freeze child running");
    if (pid <= 0)
        return;
    /* Workers publish their tids from their own threads. */
    for (spin = 0; spin < 100 && !shared->tids[0]; spin++)
        usleep(10000);
    worker_tid = shared->tids[0];
    CHECK(worker_tid > 0, "worker tid reported");
    CHECK(counter_advances(&shared->counter), "workers counter moving");

    /* Validation before any freeze exists. */
    freeze_child_req(&req, 1, 0x8000, 0);
    PRCTL_ERR("reject unknown flag", EINVAL, PR_YGGDRASIL_FREEZE, &req, 0, 0, 0);
    freeze_child_req(&req, 1, YGG_FREEZE_F_TARGET_PID, 0);
    PRCTL_ERR("reject target-pid zero", EINVAL, PR_YGGDRASIL_FREEZE, &req, 0,
              0, 0);
    freeze_child_req(&req, 0, 0, 0);
    PRCTL_ERR("thaw without freeze", ESRCH, PR_YGGDRASIL_FREEZE, &req, 0, 0, 0);

    /* Self-freeze: the child main thread stays runnable, workers stop. */
    pipe_putc(cpipe[1], 'F');
    pipe_read_u64(rpipe[0], &answer);
    CHECK_EQ(answer, 0ULL, "self freeze returns 0");
    CHECK(counter_frozen(&shared->counter), "workers stopped by self freeze");
    CHECK(status_field(worker_tid, "State", state, sizeof(state)) == 0 &&
          state[0] == 'T', "worker shows stopped state");
    CHECK(status_field(pid, "State", state, sizeof(state)) == 0 &&
          state[0] != 'T' && state[0] != 't',
          "freezing thread keeps running");
    pipe_putc(cpipe[1], 'T');
    pipe_read_u64(rpipe[0], &answer);
    CHECK_EQ(answer, (uint64_t)EPERM, "pthread_create blocked while frozen");
    pipe_putc(cpipe[1], 'R');
    pipe_read_u64(rpipe[0], &answer);
    CHECK_EQ(answer, 0ULL, "self thaw returns 0");
    CHECK(counter_advances(&shared->counter), "workers resumed by thaw");
    pipe_putc(cpipe[1], 'C');
    pipe_read_u64(rpipe[0], &answer);
    CHECK_EQ(answer, 0ULL, "pthread_create allowed after thaw");

    /* Outside freeze of the whole child from the (root) parent. */
    freeze_child_req(&req, 1, YGG_FREEZE_F_TARGET_PID, (uint32_t)pid);
    PRCTL_OK("freeze target pid", PR_YGGDRASIL_FREEZE, &req, 0, 0, 0);
    CHECK(counter_frozen(&shared->counter), "target stopped by pid freeze");
    CHECK(status_field(pid, "State", state, sizeof(state)) == 0 &&
          state[0] == 'T', "target main thread stopped");
    PRCTL_ERR("second freeze rejected", EBUSY, PR_YGGDRASIL_FREEZE, &req, 0,
              0, 0);
    freeze_child_req(&req, 0, 0, 0);
    PRCTL_ERR("thaw of other group needs pid", ESRCH, PR_YGGDRASIL_FREEZE,
              &req, 0, 0, 0);
    freeze_child_req(&req, 0, YGG_FREEZE_F_TARGET_PID, (uint32_t)pid);
    PRCTL_OK("thaw target pid", PR_YGGDRASIL_FREEZE, &req, 0, 0, 0);
    CHECK(counter_advances(&shared->counter), "target resumed by pid thaw");
    pipe_putc(cpipe[1], 'C');
    pipe_read_u64(rpipe[0], &answer);
    CHECK_EQ(answer, 0ULL, "target thread creation after thaw");

    /* Freezer-thread death auto-thaws the group. */
    pipe_putc(cpipe[1], 'Z');
    pipe_read_u64(rpipe[0], &answer);
    CHECK_EQ(answer, 0ULL, "freezer thread froze group");
    CHECK(counter_advances(&shared->counter), "owner death auto-thaws group");

    pipe_putc(cpipe[1], 'Q');
    waitpid(pid, NULL, 0);
}

/* ------------------------------------------------------------------ */
/* Driver                                                             */
/* ------------------------------------------------------------------ */

struct test_feature {
    const char *name;
    void (*fn)(void);
    int needs_root;
};

static const struct test_feature g_features[] = {
    { "core", test_core, 0 },
    { "memfd", test_memfd, 0 },
    { "merge", test_merge_view, 0 },
    { "maps", test_maps_policy, 0 },
    { "dbg", test_hide_dbg, 0 },
    { "ptrace", test_ptrace_spoof, 0 },
    { "label", test_label_spoof, 0 },
    { "hwbp", test_hwbp, 0 },
    { "syscall", test_syscall_monitor, 1 },
    { "stramp", test_stramp, 1 },
    { "freeze", test_freeze, 1 },
};

/* Best-efforest restore of neutral module state between sections. */
static void reset_module_state(void)
{
    struct ygg_maps_request mreq;
    struct ygg_syscall_rule_request sreq;
    struct ygg_hide_dbg_request dreq;
    struct ygg_ptrace_spoof_request preq;
    struct ygg_label_spoof_request lreq;
    struct ygg_hwbp_request hreq;
    struct ygg_hwbp_observe_request oreq;
    struct ygg_syscall_read_event_request rreq;
    struct ygg_freeze_request zreq;

    maps_init_request(&mreq, YGG_MAPS_OP_CLEAR, 0, 0);
    mreq.flags |= YGG_MAPS_F_ALL_SCOPES;
    prctl(PR_YGGDRASIL_MAPS_POLICY, &mreq, 0, 0, 0);

    syscall_rule_init(&sreq);
    sreq.op = YGG_SYSCALL_RULE_CLEAR;
    sreq.syscall_nr = YGG_SYSCALL_NR_ALL;
    prctl(PR_YGGDRASIL_SYSCALL_CONFIG, &sreq, 0, 0, 0);

    {
        struct ygg_stramp_request zreq;

        stramp_rule_init(&zreq, YGG_STRAMP_RULE_CLEAR);
        zreq.syscall_nr = YGG_SYSCALL_NR_ALL;
        prctl(PR_YGGDRASIL_SYSCALL_TRAMPOLINE, &zreq, 0, 0, 0);
    }

    memset(&rreq, 0, sizeof(rreq));
    rreq.size = YGG_SYSCALL_READ_REQUEST_SIZE;
    rreq.version = YGG_SYSCALL_ABI_VERSION;
    rreq.flags = YGG_SYSCALL_READ_F_UNREGISTER;
    prctl(PR_YGGDRASIL_SYSCALL_READ_EVENT, &rreq, 0, 0, 0);

    prctl(PR_YGGDRASIL_VMA_MERGE_VIEW, 0, 0, 0, 0);

    memset(&dreq, 0, sizeof(dreq));
    prctl(PR_YGGDRASIL_HIDE_DBG, &dreq, 0, 0, 0);

    memset(&preq, 0, sizeof(preq));
    prctl(PR_YGGDRASIL_PTRACE_SPOOF, &preq, 0, 0, 0);

    memset(&lreq, 0, sizeof(lreq));
    lreq.enable = 1;   /* module default: globally enabled at load */
    prctl(PR_YGGDRASIL_LABEL_SPOOF, &lreq, 0, 0, 0);

    memset(&hreq, 0, sizeof(hreq));
    prctl(PR_YGGDRASIL_HWBP_DETACH, &hreq, 0, 0, 0);

    memset(&oreq, 0, sizeof(oreq));
    prctl(PR_YGGDRASIL_HWBP_UNOBSERVE, &oreq, 0, 0, 0);

    /* Thaw with no scope: releases whatever group is still frozen. */
    memset(&zreq, 0, sizeof(zreq));
    prctl(PR_YGGDRASIL_FREEZE, &zreq, 0, 0, 0);
}

int main(int argc, char **argv)
{
    const char *filter = argc > 1 ? argv[1] : NULL;
    size_t i;

    setvbuf(stdout, NULL, _IONBF, 0);
    printf("Yggdrasil KPM unified self-test (pid %d uid %d)\n", (int)getpid(),
           (int)getuid());

    if (prctl(PR_YGGDRASIL_AVAILABLE, 0, 0, 0, 0) != 1) {
        printf("FATAL: Yggdrasil module not available (prctl not hooked).\n"
               "       Load the KPM first: kpatch <SUPERKEY> kpm load "
               "Yggdrasil.kpm\n");
        return 2;
    }
    printf("module version: 0x%lx\n",
           (unsigned long)prctl(PR_YGGDRASIL_GET_VERSION, 0, 0, 0, 0));

    for (i = 0; i < sizeof(g_features) / sizeof(g_features[0]); i++) {
        const struct test_feature *f = &g_features[i];

        if (filter && !strstr(f->name, filter))
            continue;
        if (f->needs_root && getuid() != 0) {
            printf("\n== %s: SKIPPED (needs root) ==\n", f->name);
            continue;
        }
        printf("\n== feature: %s ==\n", f->name);
        reset_module_state();
        f->fn();
        reap_children();
    }

    reset_module_state();
    printf("\n====================\n");
    printf("summary: %d checks, %d failed\n", g_checks, g_failed);
    if (g_failed == 0)
        printf("ALL PASS\n");
    else
        printf("RESULT: FAIL (%d)\n", g_failed);
    return g_failed ? 1 : 0;
}
