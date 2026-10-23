/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Yggdrasil syscall argument formatting - shared by the userspace helper and
 * the self-test.
 *
 * Two things live here:
 *   - the capture schema table, which says which syscall arguments are
 *     auto-captured (and how), and
 *   - the renderer that turns an event's raw registers plus any captured bytes
 *     into a symbolic "call:" line.
 *
 * It deliberately carries no prctl plumbing so the self-test can exercise the
 * formatting without a kernel round trip.
 */

#ifndef _KPM_YGGDRASIL_SYSCALL_FORMAT_H_
#define _KPM_YGGDRASIL_SYSCALL_FORMAT_H_

#include <arpa/inet.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <map>
#include <string>
#include <vector>

#include "yggdrasil.h"

struct syscall_capture_template {
    uint8_t arg_index;
    uint8_t phase_mask;
    uint8_t kind;
    uint8_t length_source;
    uint8_t length_arg;
    uint16_t flags;
    uint16_t schema_id;
    uint32_t fixed_length;
    uint32_t max_length;
};

struct arm64_syscall_schema {
    uint32_t nr;
    const char *name;
    uint8_t capture_count;
    struct syscall_capture_template captures[2];
};

static const struct arm64_syscall_schema arm64_syscall_schemas[] = {
    { 34, "mkdirat", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 35, "unlinkat", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 36, "symlinkat", 2, {
        { 0, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
        { 2, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 37, "linkat", 2, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
        { 3, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 38, "renameat", 2, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
        { 3, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 43, "statfs", 1, {
        { 0, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 48, "faccessat", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 49, "chdir", 1, {
        { 0, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 56, "openat", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 63, "read", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG_RETURN_MIN, 2,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 64, "write", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 67, "pread64", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG_RETURN_MIN, 2,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 68, "pwrite64", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 78, "readlinkat", 2, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
        { 2, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_RETURN, 0,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 79, "newfstatat", 2, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
        { 2, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_STAT,
          128, 128 },
    } },
    { 113, "clock_gettime", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY,
          YGG_SYSCALL_SCHEMA_TIMESPEC64, 16, 16 },
    } },
    { 200, "bind", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0,
          YGG_SYSCALL_SCHEMA_SOCKADDR, 0, 128 },
    } },
    { 203, "connect", 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0,
          YGG_SYSCALL_SCHEMA_SOCKADDR, 0, 128 },
    } },
    { 221, "execve", 1, {
        { 0, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 291, "statx", 2, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
        { 4, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_STATX,
          256, 256 },
    } },
};


static const struct arm64_syscall_schema *find_arm64_syscall_schema(uint32_t nr)
{
    for (const auto &schema : arm64_syscall_schemas) {
        if (schema.nr == nr)
            return &schema;
    }
    return NULL;
}

static const char *arm64_syscall_name(uint32_t nr)
{
    const struct arm64_syscall_schema *schema =
        find_arm64_syscall_schema(nr);

    if (schema)
        return schema->name;
    switch (nr) {
    case 57: return "close";
    case 93: return "exit";
    case 94: return "exit_group";
    case 167: return "prctl";
    case 172: return "getpid";
    case 173: return "getppid";
    case 174: return "getuid";
    case 175: return "geteuid";
    case 176: return "getgid";
    case 177: return "getegid";
    case 178: return "gettid";
    case 198: return "socket";
    case 201: return "listen";
    case 202: return "accept";
    case 204: return "getsockname";
    case 205: return "getpeername";
    case 206: return "sendto";
    case 207: return "recvfrom";
    case 208: return "setsockopt";
    case 209: return "getsockopt";
    case 210: return "shutdown";
    case 211: return "sendmsg";
    case 212: return "recvmsg";
    case 214: return "brk";
    case 215: return "munmap";
    case 222: return "mmap";
    case 226: return "mprotect";
    /* 437 openat2 keeps its name, but no longer auto-parses its arguments. */
    case 437: return "openat2";
    default: return "unknown";
    }
}


static int populate_auto_capture_descriptors(
    uint32_t syscall_nr, uint32_t trace_flags, uint32_t capture_limit,
    struct ygg_syscall_rule_request *request)
{
    const struct arm64_syscall_schema *schema =
        find_arm64_syscall_schema(syscall_nr);
    uint8_t enabled_phases = 0;

    if (!schema || !request)
        return -1;
    if (trace_flags & YGG_SYSCALL_F_TRACE_ENTER)
        enabled_phases |= YGG_SYSCALL_CAPTURE_PHASE_ENTER;
    if (trace_flags & YGG_SYSCALL_F_TRACE_EXIT)
        enabled_phases |= YGG_SYSCALL_CAPTURE_PHASE_EXIT;

    request->capture_total_limit = capture_limit;
    for (size_t i = 0; i < schema->capture_count; i++) {
        const struct syscall_capture_template *source = &schema->captures[i];
        struct ygg_syscall_capture_desc *dest;

        if (!(source->phase_mask & enabled_phases))
            continue;
        if (request->capture_count >= YGG_SYSCALL_CAPTURE_COUNT_MAX)
            return -1;

        dest = &request->captures[request->capture_count++];
        dest->arg_index = source->arg_index;
        dest->phase_mask = source->phase_mask & enabled_phases;
        dest->kind = source->kind;
        dest->length_source = source->length_source;
        dest->length_arg = source->length_arg;
        dest->pointer_source = YGG_SYSCALL_CAPTURE_POINTER_EFFECTIVE;
        dest->flags = source->flags;
        dest->schema_id = source->schema_id;
        dest->fixed_length = source->fixed_length;
        dest->max_length = std::min(source->max_length, capture_limit);
    }

    return request->capture_count ? 0 : -2;
}

static std::string escape_syscall_bytes(const uint8_t *bytes, size_t size,
                                        bool stop_at_nul)
{
    static const char hex[] = "0123456789abcdef";
    std::string escaped;

    escaped.reserve(size);
    for (size_t i = 0; i < size; i++) {
        unsigned char byte = bytes[i];

        if (!byte && stop_at_nul)
            break;
        switch (byte) {
        case '\\':
            escaped += "\\\\";
            break;
        case '"':
            escaped += "\\\"";
            break;
        case '\n':
            escaped += "\\n";
            break;
        case '\r':
            escaped += "\\r";
            break;
        case '\t':
            escaped += "\\t";
            break;
        default:
            if (byte >= 0x20 && byte <= 0x7e) {
                escaped.push_back((char)byte);
            } else {
                escaped += "\\x";
                escaped.push_back(hex[byte >> 4]);
                escaped.push_back(hex[byte & 0xf]);
            }
            break;
        }
    }
    return escaped;
}

static std::string escape_syscall_comm(const char *comm, size_t size)
{
    return escape_syscall_bytes((const uint8_t *)comm, size, true);
}

static uint16_t read_le16(const uint8_t *data)
{
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8);
}

static uint32_t read_le32(const uint8_t *data)
{
    return (uint32_t)data[0] |
           ((uint32_t)data[1] << 8) |
           ((uint32_t)data[2] << 16) |
           ((uint32_t)data[3] << 24);
}

static uint64_t read_le64(const uint8_t *data)
{
    return (uint64_t)read_le32(data) |
           ((uint64_t)read_le32(data + 4) << 32);
}

static uint16_t read_be16(const uint8_t *data)
{
    return ((uint16_t)data[0] << 8) | (uint16_t)data[1];
}

static uint32_t read_be32(const uint8_t *data)
{
    return ((uint32_t)data[0] << 24) |
           ((uint32_t)data[1] << 16) |
           ((uint32_t)data[2] << 8) |
           (uint32_t)data[3];
}

static std::string syscall_hex_bytes(const uint8_t *data, size_t size)
{
    static const char hex[] = "0123456789abcdef";
    std::string output;

    output.reserve(size * 2U);
    for (size_t i = 0; i < size; i++) {
        output.push_back(hex[data[i] >> 4]);
        output.push_back(hex[data[i] & 0xf]);
    }
    return output;
}


struct syscall_capture_view {
    struct ygg_syscall_capture_tlv tlv;
    const uint8_t *data;
};


static const char *syscall_capture_kind_name(uint8_t kind)
{
    switch (kind) {
    case YGG_SYSCALL_CAPTURE_CSTRING: return "cstring";
    case YGG_SYSCALL_CAPTURE_BYTES: return "bytes";
    case YGG_SYSCALL_CAPTURE_STRUCT: return "struct";
    default: return "unknown";
    }
}

static const char *syscall_capture_schema_name(uint16_t schema_id)
{
    switch (schema_id) {
    case YGG_SYSCALL_SCHEMA_NONE: return "none";
    case YGG_SYSCALL_SCHEMA_OPEN_HOW: return "open_how";
    case YGG_SYSCALL_SCHEMA_STATX: return "statx";
    case YGG_SYSCALL_SCHEMA_TIMESPEC64: return "timespec64";
    case YGG_SYSCALL_SCHEMA_SOCKADDR: return "sockaddr";
    case YGG_SYSCALL_SCHEMA_STAT: return "stat";
    default: return "unknown";
    }
}

static const char *syscall_capture_pointer_name(uint8_t source)
{
    switch (source) {
    case YGG_SYSCALL_CAPTURE_POINTER_ORIGINAL: return "original";
    case YGG_SYSCALL_CAPTURE_POINTER_EFFECTIVE: return "effective";
    default: return "unknown";
    }
}

static std::string syscall_capture_status_name(uint32_t status)
{
    std::string output;

    if (!status)
        return "ok";
    const struct {
        uint32_t bit;
        const char *name;
    } names[] = {
        { YGG_SYSCALL_CAPTURE_STATUS_NULL, "null" },
        { YGG_SYSCALL_CAPTURE_STATUS_FAULT, "fault" },
        { YGG_SYSCALL_CAPTURE_STATUS_TRUNCATED, "truncated" },
        { YGG_SYSCALL_CAPTURE_STATUS_SKIPPED, "skipped" },
        { YGG_SYSCALL_CAPTURE_STATUS_BUDGET, "budget" },
    };

    for (const auto &entry : names) {
        if (!(status & entry.bit))
            continue;
        if (!output.empty())
            output += '|';
        output += entry.name;
    }
    return output;
}

static std::string decode_open_how(const uint8_t *data, size_t size)
{
    char output[256];

    if (size < 8)
        return "{partial=true hex=" + syscall_hex_bytes(data, size) + "}";
    if (size < 16) {
        snprintf(output, sizeof(output), "{flags=0x%" PRIx64 " partial=true}",
                 read_le64(data));
        return output;
    }
    if (size < 24) {
        snprintf(output, sizeof(output),
                 "{flags=0x%" PRIx64 " mode=%#" PRIo64 " partial=true}",
                 read_le64(data), read_le64(data + 8));
        return output;
    }
    snprintf(output, sizeof(output),
             "{flags=0x%" PRIx64 " mode=%#" PRIo64
             " resolve=0x%" PRIx64 "}",
             read_le64(data), read_le64(data + 8), read_le64(data + 16));
    return output;
}

static std::string decode_timespec64(const uint8_t *data, size_t size)
{
    char output[192];

    if (size < 16)
        return "{partial=true hex=" + syscall_hex_bytes(data, size) + "}";
    snprintf(output, sizeof(output), "{tv_sec=%" PRId64 " tv_nsec=%" PRId64 "}",
             (int64_t)read_le64(data), (int64_t)read_le64(data + 8));
    return output;
}

static std::string decode_statx(const uint8_t *data, size_t size)
{
    char output[768];
    int written;

    if (size < 64)
        return "{partial=true hex=" + syscall_hex_bytes(data, size) + "}";
    written = snprintf(output, sizeof(output),
                       "{mask=0x%x blksize=%u attributes=0x%" PRIx64
                       " nlink=%u uid=%u gid=%u mode=0%o ino=%" PRIu64
                       " size=%" PRIu64 " blocks=%" PRIu64
                       " attributes_mask=0x%" PRIx64,
                       read_le32(data), read_le32(data + 4),
                       read_le64(data + 8), read_le32(data + 16),
                       read_le32(data + 20), read_le32(data + 24),
                       read_le16(data + 28), read_le64(data + 32),
                       read_le64(data + 40), read_le64(data + 48),
                       read_le64(data + 56));
    if (written < 0 || (size_t)written >= sizeof(output))
        return "{hex=" + syscall_hex_bytes(data, size) + "}";

    std::string decoded(output, (size_t)written);
    if (size >= 128) {
        snprintf(output, sizeof(output),
                 " atime=%" PRId64 ".%09u btime=%" PRId64 ".%09u"
                 " ctime=%" PRId64 ".%09u mtime=%" PRId64 ".%09u",
                 (int64_t)read_le64(data + 64), read_le32(data + 72),
                 (int64_t)read_le64(data + 80), read_le32(data + 88),
                 (int64_t)read_le64(data + 96), read_le32(data + 104),
                 (int64_t)read_le64(data + 112), read_le32(data + 120));
        decoded += output;
    }
    if (size >= 160) {
        snprintf(output, sizeof(output),
                 " rdev=%u:%u dev=%u:%u mnt_id=%" PRIu64
                 " dio_mem_align=%u dio_offset_align=%u",
                 read_le32(data + 128), read_le32(data + 132),
                 read_le32(data + 136), read_le32(data + 140),
                 read_le64(data + 144), read_le32(data + 152),
                 read_le32(data + 156));
        decoded += output;
    }
    decoded += '}';
    return decoded;
}

/*
 * struct stat differs from struct statx, so it needs its own layout. These are
 * the arm64 (asm-generic) offsets; the kernel writes the native 128-byte
 * struct stat for newfstatat.
 */
static std::string decode_stat(const uint8_t *data, size_t size)
{
    char output[512];
    int written;

    if (size < 128)
        return "{partial=true hex=" + syscall_hex_bytes(data, size) + "}";
    written = snprintf(output, sizeof(output),
                       "{mode=0%o size=%" PRId64 " uid=%u gid=%u nlink=%u"
                       " ino=%" PRIu64 " dev=%" PRIu64 " rdev=%" PRIu64
                       " blksize=%d blocks=%" PRId64
                       " atime=%" PRId64 ".%09" PRIu64
                       " mtime=%" PRId64 ".%09" PRIu64
                       " ctime=%" PRId64 ".%09" PRIu64 "}",
                       read_le32(data + 16), (int64_t)read_le64(data + 48),
                       read_le32(data + 24), read_le32(data + 28),
                       read_le32(data + 20), read_le64(data + 8),
                       read_le64(data), read_le64(data + 32),
                       (int32_t)read_le32(data + 56),
                       (int64_t)read_le64(data + 64),
                       (int64_t)read_le64(data + 72), read_le64(data + 80),
                       (int64_t)read_le64(data + 88), read_le64(data + 96),
                       (int64_t)read_le64(data + 104), read_le64(data + 112));
    if (written < 0 || (size_t)written >= sizeof(output))
        return "{hex=" + syscall_hex_bytes(data, size) + "}";
    return std::string(output, (size_t)written);
}

static std::string decode_sockaddr(const uint8_t *data, size_t size)
{
    char address[INET6_ADDRSTRLEN] = {};
    char output[384];
    uint16_t family;

    if (size < 2)
        return "{partial=true hex=" + syscall_hex_bytes(data, size) + "}";
    family = read_le16(data);
    if (family == AF_UNIX) {
        if (size == 2)
            return "{family=AF_UNIX path=\"\"}";
        bool abstract = data[2] == 0;
        size_t offset = abstract ? 3U : 2U;
        std::string path = offset <= size ?
            escape_syscall_bytes(data + offset, size - offset, !abstract) : "";

        return std::string("{family=AF_UNIX ") +
               (abstract ? "abstract=\"" : "path=\"") + path + "\"}";
    }
    if (family == AF_INET && size >= 8) {
        if (!inet_ntop(AF_INET, data + 4, address, sizeof(address)))
            snprintf(address, sizeof(address), "<invalid>");
        snprintf(output, sizeof(output),
                 "{family=AF_INET address=%s port=%u}", address,
                 read_be16(data + 2));
        return output;
    }
    if (family == AF_INET6 && size >= 28) {
        if (!inet_ntop(AF_INET6, data + 8, address, sizeof(address)))
            snprintf(address, sizeof(address), "<invalid>");
        snprintf(output, sizeof(output),
                 "{family=AF_INET6 address=%s port=%u flowinfo=%u scope_id=%u}",
                 address, read_be16(data + 2), read_be32(data + 4),
                 read_le32(data + 24));
        return output;
    }
#ifdef AF_NETLINK
    if (family == AF_NETLINK && size >= 12) {
        snprintf(output, sizeof(output),
                 "{family=AF_NETLINK pid=%u groups=0x%x}",
                 read_le32(data + 4), read_le32(data + 8));
        return output;
    }
#endif
    snprintf(output, sizeof(output), "{family=%u hex=%s}", family,
             syscall_hex_bytes(data + 2, size - 2).c_str());
    return output;
}

static std::string decode_syscall_capture(
    const struct ygg_syscall_capture_tlv *tlv, const uint8_t *data)
{
    if (tlv->kind == YGG_SYSCALL_CAPTURE_CSTRING) {
        return "\"" + escape_syscall_bytes(data, tlv->captured_length, true) +
               "\"";
    }
    if (tlv->kind == YGG_SYSCALL_CAPTURE_BYTES) {
        return "\"" + escape_syscall_bytes(data, tlv->captured_length, false) +
               "\"";
    }
    switch (tlv->schema_id) {
    case YGG_SYSCALL_SCHEMA_OPEN_HOW:
        return decode_open_how(data, tlv->captured_length);
    case YGG_SYSCALL_SCHEMA_STATX:
        return decode_statx(data, tlv->captured_length);
    case YGG_SYSCALL_SCHEMA_TIMESPEC64:
        return decode_timespec64(data, tlv->captured_length);
    case YGG_SYSCALL_SCHEMA_SOCKADDR:
        return decode_sockaddr(data, tlv->captured_length);
    case YGG_SYSCALL_SCHEMA_STAT:
        return decode_stat(data, tlv->captured_length);
    default:
        return "{hex=" + syscall_hex_bytes(data, tlv->captured_length) + "}";
    }
}


enum syscall_arg_kind {
    ARG_HEX = 0,          /* opaque address */
    ARG_DIRFD,            /* AT_FDCWD or a descriptor */
    ARG_PATH,             /* NUL-terminated path (captured) */
    ARG_OUT_BUF,          /* output byte range (captured) */
    ARG_OUT_STAT,         /* struct stat output (captured) */
    ARG_MODE,             /* file mode, octal */
    ARG_INT,              /* signed decimal */
    ARG_UINT,             /* unsigned decimal */
    ARG_AT_REMOVEDIR,     /* unlinkat flags */
    ARG_AT_LINK,          /* linkat flags */
    ARG_AT_STAT,          /* newfstatat flags */
    ARG_ACCESS_MODE,      /* R_OK/W_OK/X_OK */
    ARG_PROT,             /* PROT_* */
};

struct syscall_arg_spec {
    const char *name;
    uint8_t kind;
};

struct arm64_syscall_signature {
    uint32_t nr;
    uint8_t arg_count;
    struct syscall_arg_spec args[YGG_SYSCALL_ARG_COUNT];
};

static const struct arm64_syscall_signature arm64_syscall_signatures[] = {
    { 34, 3, { { "dirfd", ARG_DIRFD },
               { "pathname", ARG_PATH },
               { "mode", ARG_MODE } } },
    { 35, 3, { { "dirfd", ARG_DIRFD },
               { "pathname", ARG_PATH },
               { "flags", ARG_AT_REMOVEDIR } } },
    { 36, 3, { { "target", ARG_PATH },
               { "newdirfd", ARG_DIRFD },
               { "linkpath", ARG_PATH } } },
    { 37, 5, { { "olddirfd", ARG_DIRFD },
               { "oldpath", ARG_PATH },
               { "newdirfd", ARG_DIRFD },
               { "newpath", ARG_PATH },
               { "flags", ARG_AT_LINK } } },
    { 38, 4, { { "olddirfd", ARG_DIRFD },
               { "oldpath", ARG_PATH },
               { "newdirfd", ARG_DIRFD },
               { "newpath", ARG_PATH } } },
    { 43, 1, { { "pathname", ARG_PATH } } },
    { 48, 3, { { "dirfd", ARG_DIRFD },
               { "pathname", ARG_PATH },
               { "mode", ARG_ACCESS_MODE } } },
    { 49, 1, { { "pathname", ARG_PATH } } },
    { 78, 4, { { "dirfd", ARG_DIRFD },
               { "pathname", ARG_PATH },
               { "buf", ARG_OUT_BUF },
               { "bufsiz", ARG_INT } } },
    { 79, 4, { { "dirfd", ARG_DIRFD },
               { "pathname", ARG_PATH },
               { "statbuf", ARG_OUT_STAT },
               { "flags", ARG_AT_STAT } } },
    { 226, 3, { { "addr", ARG_HEX },
                { "len", ARG_UINT },
                { "prot", ARG_PROT } } },
};

static const struct arm64_syscall_signature *find_arm64_syscall_signature(
    uint32_t nr)
{
    for (const auto &signature : arm64_syscall_signatures) {
        if (signature.nr == nr)
            return &signature;
    }
    return NULL;
}

struct syscall_flag_name {
    uint64_t value;
    const char *name;
};

static std::string format_syscall_flags(uint64_t value,
                                        const struct syscall_flag_name *names,
                                        size_t count, const char *zero_name)
{
    std::string output;
    uint64_t remaining = value;

    for (size_t i = 0; i < count; i++) {
        if (!(value & names[i].value))
            continue;
        if (!output.empty())
            output += '|';
        output += names[i].name;
        remaining &= ~names[i].value;
    }
    if (remaining || output.empty()) {
        char tail[32];

        snprintf(tail, sizeof(tail), "%s0x%" PRIx64,
                 output.empty() ? "" : "|", remaining);
        output += tail;
    }
    return value ? output : zero_name;
}

static std::string format_syscall_arg(uint8_t kind, uint64_t value)
{
    static const struct syscall_flag_name removedir_flags[] = {
        { AT_REMOVEDIR, "AT_REMOVEDIR" },
    };
    static const struct syscall_flag_name link_flags[] = {
        { AT_SYMLINK_FOLLOW, "AT_SYMLINK_FOLLOW" },
        { AT_EMPTY_PATH, "AT_EMPTY_PATH" },
    };
    static const struct syscall_flag_name stat_flags[] = {
        { AT_SYMLINK_NOFOLLOW, "AT_SYMLINK_NOFOLLOW" },
        { AT_NO_AUTOMOUNT, "AT_NO_AUTOMOUNT" },
        { AT_EMPTY_PATH, "AT_EMPTY_PATH" },
        { AT_STATX_FORCE_SYNC, "AT_STATX_FORCE_SYNC" },
        { AT_STATX_DONT_SYNC, "AT_STATX_DONT_SYNC" },
    };
    static const struct syscall_flag_name access_flags[] = {
        { R_OK, "R_OK" },
        { W_OK, "W_OK" },
        { X_OK, "X_OK" },
    };
    static const struct syscall_flag_name prot_flags[] = {
        { PROT_READ, "PROT_READ" },
        { PROT_WRITE, "PROT_WRITE" },
        { PROT_EXEC, "PROT_EXEC" },
        { PROT_SEM, "PROT_SEM" },
        { PROT_GROWSDOWN, "PROT_GROWSDOWN" },
        { PROT_GROWSUP, "PROT_GROWSUP" },
    };
    char output[64];

    switch (kind) {
    case ARG_DIRFD:
        if ((int32_t)value == AT_FDCWD)
            return "AT_FDCWD";
        snprintf(output, sizeof(output), "%" PRId32, (int32_t)value);
        return output;
    case ARG_MODE:
        snprintf(output, sizeof(output), "0%o", (unsigned)(value & 07777));
        return output;
    case ARG_INT:
        snprintf(output, sizeof(output), "%" PRId32, (int32_t)value);
        return output;
    case ARG_UINT:
        snprintf(output, sizeof(output), "%" PRIu64, value);
        return output;
    case ARG_AT_REMOVEDIR:
        return format_syscall_flags(value, removedir_flags,
                                    sizeof(removedir_flags) /
                                        sizeof(removedir_flags[0]),
                                    "0");
    case ARG_AT_LINK:
        return format_syscall_flags(value, link_flags,
                                    sizeof(link_flags) /
                                        sizeof(link_flags[0]),
                                    "0");
    case ARG_AT_STAT:
        return format_syscall_flags(value, stat_flags,
                                    sizeof(stat_flags) /
                                        sizeof(stat_flags[0]),
                                    "0");
    case ARG_ACCESS_MODE:
        return format_syscall_flags(value, access_flags,
                                    sizeof(access_flags) /
                                        sizeof(access_flags[0]),
                                    "F_OK");
    case ARG_PROT:
        return format_syscall_flags(value, prot_flags,
                                    sizeof(prot_flags) /
                                        sizeof(prot_flags[0]),
                                    "PROT_NONE");
    case ARG_PATH:
    case ARG_OUT_BUF:
    case ARG_OUT_STAT:
    case ARG_HEX:
    default:
        snprintf(output, sizeof(output), "0x%" PRIx64, value);
        return output;
    }
}

static bool syscall_arg_kind_is_pointer(uint8_t kind)
{
    return kind == ARG_PATH || kind == ARG_OUT_BUF || kind == ARG_OUT_STAT;
}

static const struct syscall_capture_view *find_syscall_arg_capture(
    const std::vector<struct syscall_capture_view> &captures, uint8_t arg_index)
{
    for (const auto &capture : captures) {
        if (capture.tlv.arg_index == arg_index)
            return &capture;
    }
    return NULL;
}

/*
 * Arguments captured on enter (paths) and on exit (output buffers) land in
 * different event records, so one "call:" line needs both. Enter events stash
 * their rendered arguments in "pending" keyed by call_id; the matching exit
 * event merges them back in. The store is bounded because an enter event whose
 * syscall never returns (or whose rule lacks exit tracing) would linger.
 */
#define YGG_SYSCALL_PENDING_ARGS_MAX  1024U

typedef std::map<uint64_t, std::vector<std::string>> syscall_pending_args;

static std::string format_syscall_call_line(
    const struct ygg_syscall_event *event,
    const std::vector<struct syscall_capture_view> &captures,
    syscall_pending_args *pending)
{
    const struct arm64_syscall_signature *signature =
        find_arm64_syscall_signature(event->syscall_nr);
    std::vector<std::string> stored;
    std::string line;
    bool is_exit = event->phase == YGG_SYSCALL_PHASE_EXIT;

    if (!signature)
        return line;

    /*
     * "stored" holds only the arguments this event actually captured, so an
     * empty entry means "no value here" and the matching phase supplies it.
     */
    stored.resize(signature->arg_count);
    for (uint8_t i = 0; i < signature->arg_count; i++) {
        const struct syscall_capture_view *capture;

        /*
         * Only pointer arguments can be satisfied by a capture; capturing at a
         * scalar argument index would read the register value as an address.
         */
        if (!syscall_arg_kind_is_pointer(signature->args[i].kind))
            continue;
        capture = find_syscall_arg_capture(captures, i);
        if (capture && !capture->tlv.status)
            stored[i] = decode_syscall_capture(&capture->tlv, capture->data);
    }

    if (is_exit && pending) {
        syscall_pending_args::const_iterator it = pending->find(event->call_id);

        if (it != pending->end()) {
            for (uint8_t i = 0; i < signature->arg_count; i++) {
                if (!stored[i].empty())
                    continue;
                if (i < it->second.size())
                    stored[i] = it->second[i];
            }
        }
    }

    line = arm64_syscall_name(event->syscall_nr);
    line += '(';
    for (uint8_t i = 0; i < signature->arg_count; i++) {
        std::string value = stored[i];

        if (value.empty()) {
            value = format_syscall_arg(signature->args[i].kind,
                                       event->args_after[i]);
        }
        if (i)
            line += ", ";
        line += signature->args[i].name;
        line += '=';
        line += value;
    }
    line += ')';

    if (pending) {
        if (is_exit) {
            pending->erase(event->call_id);
        } else {
            if (pending->size() >= YGG_SYSCALL_PENDING_ARGS_MAX)
                pending->erase(pending->begin());
            (*pending)[event->call_id] = stored;
        }
    }
    return line;
}

#endif /* _KPM_YGGDRASIL_SYSCALL_FORMAT_H_ */
