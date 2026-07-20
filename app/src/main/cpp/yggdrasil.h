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
#define PR_YGGDRASIL_GET_VERSION     0x5947000B

#define YGG_HIDE_DBG_F_TARGET_UID    (1U << 0)

struct ygg_hide_dbg_request {
    uint32_t enable;
    uint32_t flags;
    uint32_t uid;
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
