/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Yggdrasil KPM Module - Public ABI shared by the KPM and userspace helper.
 */

#ifndef _KPM_YGGDRASIL_H_
#define _KPM_YGGDRASIL_H_

#ifdef __KERNEL__
#include <ktypes.h>
#else
#include <stdint.h>
#define __user
typedef uint32_t u32;
#endif

#define YGG_NAME_MAX 255

/*
 * 0x5947 == "YG"
 * Keep the command space separate from upstream prctl options.
 */
#define PR_YGGDRASIL_AVAILABLE       0x59470000
#define PR_YGGDRASIL_MEMFD_CREATE    0x59470001
#define PR_YGGDRASIL_VMA_MERGE_VIEW  0x59470002
#define PR_YGGDRASIL_HWBP_ATTACH     0x59470003
#define PR_YGGDRASIL_HWBP_REPLACE    0x59470004
#define PR_YGGDRASIL_HWBP_DETACH     0x59470005
#define PR_YGGDRASIL_HWBP_OBSERVE    0x59470006
#define PR_YGGDRASIL_HWBP_UNOBSERVE  0x59470007
#define PR_YGGDRASIL_HWBP_READ_EVENT 0x59470008
#define PR_YGGDRASIL_HIDE_DBG        0x59470009
#define PR_YGGDRASIL_HIDE_MAPS       0x5947000A
#define PR_YGGDRASIL_GET_VERSION     0x5947000B
#define PR_YGGDRASIL_SYSCALL_CONFIG  0x5947000C
#define PR_YGGDRASIL_SYSCALL_READ_EVENT 0x5947000D

/* Version code returned by PR_YGGDRASIL_GET_VERSION: (major << 16) | (minor << 8) | patch */
#define YGG_VERSION_MAJOR  2
#define YGG_VERSION_MINOR  1
#define YGG_VERSION_PATCH  3
#define YGG_VERSION_CODE   ((YGG_VERSION_MAJOR << 16) | (YGG_VERSION_MINOR << 8) | YGG_VERSION_PATCH)

#define YGG__STR(x)        #x
#define YGG__XSTR(x)       YGG__STR(x)
#define YGG_VERSION_STRING \
    YGG__XSTR(YGG_VERSION_MAJOR) "." YGG__XSTR(YGG_VERSION_MINOR) "." YGG__XSTR(YGG_VERSION_PATCH)

#define YGG_HIDE_DBG_F_TARGET_UID    (1U << 0)
struct ygg_hide_dbg_request {
    uint32_t enable;
    uint32_t flags;
    uint32_t uid;
    uint32_t reserved;
};

/* Maps presentation policy ABI, shared with the current KPM. */
#define PR_YGGDRASIL_MAPS_POLICY     PR_YGGDRASIL_HIDE_MAPS
#define YGG_MAPS_ABI_VERSION         2U
#define YGG_MAPS_F_TARGET_PID        (1U << 0)
#define YGG_MAPS_F_ALL_SCOPES        (1U << 1)
#define YGG_MAPS_MAX_RULES           32U
#define YGG_MAPS_PATTERN_MAX         128U

enum ygg_maps_op {
    YGG_MAPS_OP_ADD_RULE = 1,
    YGG_MAPS_OP_REMOVE_RULE = 2,
    YGG_MAPS_OP_CLEAR = 3,
    YGG_MAPS_OP_GET = 4,
};

enum ygg_maps_action {
    YGG_MAPS_ACTION_HIDE = 1,
    YGG_MAPS_ACTION_REWRITE_PERMS = 2,
};

enum ygg_maps_match_kind {
    YGG_MAPS_MATCH_ANY = 0,
    YGG_MAPS_MATCH_PATH_SUBSTR = 1,
    YGG_MAPS_MATCH_PATH_PREFIX = 2,
    YGG_MAPS_MATCH_PATH_EXACT = 3,
    YGG_MAPS_MATCH_PATH_BASENAME = 4,
    YGG_MAPS_MATCH_ADDR_RANGE = 5,
};

#define YGG_MAPS_RULE_F_MATCH_PERMS  (1U << 0)

struct ygg_maps_rule {
    uint32_t action;
    uint32_t match_kind;
    uint32_t rule_flags;
    uint32_t reserved;
    char match_perms[4];
    char set_perms[4];
    uint64_t addr_start;
    uint64_t addr_end;
    char pattern[YGG_MAPS_PATTERN_MAX];
};

struct ygg_maps_request {
    uint32_t size;
    uint32_t version;
    uint32_t op;
    uint32_t flags;
    uint32_t pid;
    uint32_t reserved;
    struct ygg_maps_rule rule;
    uint64_t buffer;
    uint32_t buffer_size;
    uint32_t reserved2;
};

struct ygg_maps_dump {
    uint32_t size;
    uint32_t version;
    uint32_t scope_global;
    uint32_t total_rules;
    uint32_t copied_rules;
    uint32_t reserved;
};

#define YGG_SYSCALL_ARG_COUNT            6U
/* KernelPatch exposes 460 name slots; each target is checked at runtime. */
#define YGG_ARM64_SYSCALL_COUNT          460U
#define YGG_SYSCALL_NR_ALL               0xffffffffU
#define YGG_SYSCALL_ABI_VERSION           3U
#define YGG_SYSCALL_RULE_REQUEST_SIZE     256U
#define YGG_SYSCALL_EVENT_HEADER_SIZE     224U
#define YGG_SYSCALL_CAPTURE_TLV_SIZE      32U
#define YGG_SYSCALL_READ_REQUEST_SIZE     40U
#define YGG_SYSCALL_CAPTURE_COUNT_MAX     6U
#define YGG_SYSCALL_CAPTURE_BYTES_MAX     512U
#define YGG_SYSCALL_CAPTURE_PAYLOAD_MAX   1024U
#define YGG_SYSCALL_EVENT_SIZE_MAX        \
    (YGG_SYSCALL_EVENT_HEADER_SIZE + YGG_SYSCALL_CAPTURE_PAYLOAD_MAX)

#define YGG_SYSCALL_F_SINGLE_THREAD      (1U << 0)
#define YGG_SYSCALL_F_TRACE_ENTER        (1U << 1)
#define YGG_SYSCALL_F_TRACE_EXIT         (1U << 2)
#define YGG_SYSCALL_F_REPLACE_RETURN     (1U << 3)
#define YGG_SYSCALL_F_TARGET_UID         (1U << 4)
#define YGG_SYSCALL_F_CAPTURE_BACKTRACE  (1U << 5)

#define YGG_SYSCALL_EVENT_MAGIC          0x59475343U
#define YGG_SYSCALL_EVENT_VERSION        YGG_SYSCALL_ABI_VERSION
#define YGG_SYSCALL_EVENT_F_ARGS_REPLACED  (1U << 0)
#define YGG_SYSCALL_EVENT_F_RETURN_REPLACED (1U << 1)
#define YGG_SYSCALL_EVENT_F_AFTER_DROPPED   (1U << 2)
#define YGG_SYSCALL_EVENT_F_HAS_CAPTURES    (1U << 3)
#define YGG_SYSCALL_EVENT_F_CAPTURE_PARTIAL (1U << 4)
#define YGG_SYSCALL_EVENT_F_HAS_BACKTRACE   (1U << 5)

#define YGG_SYSCALL_CAPTURE_PHASE_ENTER     (1U << 0)
#define YGG_SYSCALL_CAPTURE_PHASE_EXIT      (1U << 1)

#define YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY  (1U << 0)

#define YGG_SYSCALL_CAPTURE_STATUS_NULL       (1U << 0)
#define YGG_SYSCALL_CAPTURE_STATUS_FAULT      (1U << 1)
#define YGG_SYSCALL_CAPTURE_STATUS_TRUNCATED  (1U << 2)
#define YGG_SYSCALL_CAPTURE_STATUS_SKIPPED    (1U << 3)
#define YGG_SYSCALL_CAPTURE_STATUS_BUDGET     (1U << 4)

/*
 * Backtrace capture. A rule carrying YGG_SYSCALL_F_CAPTURE_BACKTRACE snapshots
 * the user register file and a bounded slice of the user stack at trigger time,
 * delivered atomically with its syscall event by
 * PR_YGGDRASIL_SYSCALL_READ_EVENT.
 */
#define YGG_SYSCALL_BACKTRACE_EVENT_HEADER_SIZE  368U
#define YGG_SYSCALL_BACKTRACE_EVENT_MAGIC        0x59475342U
#define YGG_SYSCALL_BACKTRACE_EVENT_VERSION      YGG_SYSCALL_ABI_VERSION
#define YGG_SYSCALL_BACKTRACE_STACK_DEFAULT      (4U << 10)
#define YGG_SYSCALL_BACKTRACE_STACK_MAX          (64U << 10)

#define YGG_SYSCALL_BACKTRACE_F_USER     (1U << 0)
#define YGG_SYSCALL_BACKTRACE_F_COMPAT   (1U << 1)
#define YGG_SYSCALL_BACKTRACE_F_STACK    (1U << 2)
#define YGG_SYSCALL_BACKTRACE_F_PARTIAL  (1U << 3)

enum ygg_syscall_capture_kind {
    YGG_SYSCALL_CAPTURE_CSTRING = 1,
    YGG_SYSCALL_CAPTURE_BYTES = 2,
    YGG_SYSCALL_CAPTURE_STRUCT = 3,
};

enum ygg_syscall_capture_length_source {
    YGG_SYSCALL_CAPTURE_LENGTH_FIXED = 1,
    YGG_SYSCALL_CAPTURE_LENGTH_ARG = 2,
    YGG_SYSCALL_CAPTURE_LENGTH_RETURN = 3,
    YGG_SYSCALL_CAPTURE_LENGTH_ARG_RETURN_MIN = 4,
};

enum ygg_syscall_capture_pointer_source {
    YGG_SYSCALL_CAPTURE_POINTER_ORIGINAL = 1,
    YGG_SYSCALL_CAPTURE_POINTER_EFFECTIVE = 2,
};

enum ygg_syscall_capture_schema {
    YGG_SYSCALL_SCHEMA_NONE = 0,
    YGG_SYSCALL_SCHEMA_OPEN_HOW = 1,
    YGG_SYSCALL_SCHEMA_STATX = 2,
    YGG_SYSCALL_SCHEMA_TIMESPEC64 = 3,
    YGG_SYSCALL_SCHEMA_SOCKADDR = 4,
};

enum ygg_syscall_event_abi {
    YGG_SYSCALL_EVENT_ABI_ARM64 = 1,
};

struct ygg_syscall_capture_desc {
    uint8_t arg_index;
    uint8_t phase_mask;
    uint8_t kind;
    uint8_t length_source;
    uint8_t length_arg;
    uint8_t pointer_source;
    uint16_t flags;
    uint16_t schema_id;
    uint16_t reserved0;
    uint32_t fixed_length;
    uint32_t max_length;
    uint32_t reserved1;
};

/* The listener identity stays exempt from broad-scope trace feedback until unregister. */
#define YGG_SYSCALL_READ_F_LISTENER      (1U << 0)
#define YGG_SYSCALL_READ_F_UNREGISTER    (1U << 1)

enum ygg_syscall_rule_op {
    YGG_SYSCALL_RULE_SET = 1,
    YGG_SYSCALL_RULE_DELETE = 2,
    YGG_SYSCALL_RULE_CLEAR = 3,
};

enum ygg_syscall_event_phase {
    YGG_SYSCALL_PHASE_ENTER = 1,
    YGG_SYSCALL_PHASE_EXIT = 2,
};

struct ygg_syscall_rule_request {
    uint32_t size;
    uint32_t version;
    uint32_t op;
    uint32_t flags;
    uint32_t syscall_nr;
    union {
        /* Zero selects every task; nonzero values use the init PID namespace. */
        uint32_t target_pid;
        /* Raw real UID; YGG_SYSCALL_F_TARGET_UID distinguishes UID 0 from global. */
        uint32_t target_uid;
    };
    uint64_t arg_mask;
    uint64_t arg_values[YGG_SYSCALL_ARG_COUNT];
    uint64_t return_value;
    uint64_t user_cookie;
    /* Bytes of user stack to snapshot when YGG_SYSCALL_F_CAPTURE_BACKTRACE is set. */
    uint32_t backtrace_stack_size;
    uint32_t reserved;
    uint32_t capture_count;
    uint32_t capture_total_limit;
    struct ygg_syscall_capture_desc
        captures[YGG_SYSCALL_CAPTURE_COUNT_MAX];
};

struct ygg_syscall_event {
    uint32_t magic;
    uint32_t version;
    uint32_t header_size;
    uint32_t total_size;
    uint32_t phase;
    uint32_t abi;
    uint64_t seq;
    uint64_t dropped;
    uint64_t call_id;
    uint32_t tid;
    uint32_t tgid;
    uint32_t syscall_nr;
    uint32_t event_flags;
    uint32_t rule_flags;
    union {
        uint32_t target_pid;
        uint32_t target_uid;
    };
    uint64_t user_cookie;
    uint64_t arg_mask;
    uint64_t args_before[YGG_SYSCALL_ARG_COUNT];
    uint64_t args_after[YGG_SYSCALL_ARG_COUNT];
    uint64_t return_before;
    uint64_t return_after;
    uint32_t capture_count;
    uint32_t payload_size;
    char comm[16];
};

struct ygg_syscall_capture_tlv {
    uint16_t size;
    uint8_t arg_index;
    uint8_t kind;
    uint8_t phase;
    uint8_t pointer_source;
    uint16_t schema_id;
    uint32_t status;
    uint32_t original_length;
    uint32_t captured_length;
    uint32_t reserved;
    uint64_t address;
};

struct ygg_syscall_event_record {
    struct ygg_syscall_event event;
    uint8_t payload[YGG_SYSCALL_CAPTURE_PAYLOAD_MAX];
};

struct ygg_syscall_read_event_request {
    uint32_t size;
    uint32_t version;
    uint64_t buffer;
    uint32_t buffer_size;
    uint32_t flags;
    uint64_t backtrace_buffer;
    uint32_t backtrace_buffer_size;
    uint32_t reserved;
};

/*
 * Limit hardware-breakpoint installation to the requested TID instead of
 * following every task in the requested thread group.
 */
#define YGG_HWBP_F_SINGLE_THREAD     (1U << 0)
#define YGG_HWBP_F_FILTER_SYSTEM     (1U << 1)

#define YGG_HWBP_MODE_R              1U
#define YGG_HWBP_MODE_W              2U
#define YGG_HWBP_MODE_RW             3U
#define YGG_HWBP_MODE_X              4U

#define YGG_HWBP_EVENT_MAGIC         0x59474250U
#define YGG_HWBP_EVENT_VERSION       1U
#define YGG_HWBP_EVENT_FLAG_USER     (1U << 0)
#define YGG_HWBP_EVENT_FLAG_COMPAT   (1U << 1)
#define YGG_HWBP_EVENT_FLAG_STACK    (1U << 2)
#define YGG_HWBP_EVENT_FLAG_PARTIAL  (1U << 3)
#define YGG_HWBP_EVENT_STACK_MAX     (64U << 10)

/**
 * struct ygg_hwbp_request - Userspace ABI for hardware-breakpoint controls.
 * @target_addr: Userspace instruction address where the execute breakpoint fires.
 * @trampoline_addr: Userspace handler address that receives control on a hit.
 * @x_mask: Bit mask selecting which x0-x7 values should be overwritten.
 * @x_values: Replacement values for the selected x0-x7 registers.
 * @target_pid: Target TGID by default, or TID when YGG_HWBP_F_SINGLE_THREAD is set.
 * @flags: YGG_HWBP_F_* options controlling breakpoint scope.
 */
struct ygg_hwbp_request {
    uint64_t target_addr;
    uint64_t trampoline_addr;
    uint64_t x_mask;
    uint64_t x_values[8];
    uint32_t target_pid;
    uint32_t flags;
};

/**
 * struct ygg_hwbp_observe_request - Userspace ABI for stack-observing HWBPs.
 * @target_addr: Userspace address to watch.
 * @target_pid: Target TGID by default, or TID with YGG_HWBP_F_SINGLE_THREAD.
 * @mode: YGG_HWBP_MODE_* access mode: r, w, rw, or x.
 * @len: Watched byte length for data breakpoints; execute is forced to 4.
 * @flags: YGG_HWBP_F_* options controlling thread scope/filtering.
 * @stack_size: Bytes of user stack to snapshot per hit.
 */
struct ygg_hwbp_observe_request {
    uint64_t target_addr;
    uint32_t target_pid;
    uint32_t mode;
    uint32_t len;
    uint32_t flags;
    uint32_t stack_size;
    uint32_t reserved;
};

struct ygg_hwbp_user_regs_snapshot {
    uint64_t regs[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t pstate;
};

/**
 * struct ygg_syscall_backtrace_event - Fixed header for a syscall backtrace record.
 * @magic: YGG_SYSCALL_BACKTRACE_EVENT_MAGIC.
 * @version: YGG_SYSCALL_BACKTRACE_EVENT_VERSION.
 * @header_size: sizeof(struct ygg_syscall_backtrace_event).
 * @total_size: header_size + stack_copied_size.
 * @flags: YGG_SYSCALL_BACKTRACE_F_* describing capture mode and completeness.
 * @phase: YGG_SYSCALL_PHASE_ENTER or YGG_SYSCALL_PHASE_EXIT.
 * @seq: Monotonic backtrace sequence counter.
 * @dropped: Backtrace records dropped with paired syscall events before this one.
 * @call_id: Matches the syscall event's call_id for correlation.
 * @tid: Thread id (init PID namespace).
 * @tgid: Thread-group id (init PID namespace).
 * @syscall_nr: Intercepted syscall number.
 * @stack_requested_size: Bytes of user stack requested by the rule.
 * @stack_copied_size: Bytes of user stack actually captured (trails the header).
 * @stack_addr: User stack pointer the snapshot begins at.
 * @comm: Task command name.
 * @regs: User register snapshot for FP-walk or DWARF unwinding.
 */
struct ygg_syscall_backtrace_event {
    uint32_t magic;
    uint32_t version;
    uint32_t header_size;
    uint32_t total_size;
    uint32_t flags;
    uint32_t phase;
    uint64_t seq;
    uint64_t dropped;
    uint64_t call_id;
    uint32_t tid;
    uint32_t tgid;
    uint32_t syscall_nr;
    uint32_t stack_requested_size;
    uint32_t stack_copied_size;
    uint32_t reserved;
    uint64_t stack_addr;
    char comm[16];
    struct ygg_hwbp_user_regs_snapshot regs;
};

struct ygg_syscall_backtrace_event_record {
    struct ygg_syscall_backtrace_event event;
    uint8_t stack[YGG_SYSCALL_BACKTRACE_STACK_MAX];
};

struct ygg_hwbp_event_header {
    uint32_t magic;
    uint32_t version;
    uint32_t header_size;
    uint32_t total_size;
    uint32_t flags;
    uint64_t seq;
    uint64_t dropped;
    uint32_t tid;
    uint32_t tgid;
    uint32_t mode;
    uint32_t len;
    uint32_t stack_requested_size;
    uint32_t stack_copied_size;
    uint64_t watched_addr;
    uint64_t trigger_addr;
    uint64_t stack_addr;
    char comm[16];
    struct ygg_hwbp_user_regs_snapshot regs;
};

struct ygg_hwbp_read_event_request {
    void __user *buffer;
    uint32_t buffer_size;
    uint32_t reserved;
};

/* Subset of memfd flags we intentionally support in the first version. */
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC        0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING  0x0002U
#endif
#ifndef MFD_HUGETLB
#define MFD_HUGETLB        0x0004U
#endif

#endif
