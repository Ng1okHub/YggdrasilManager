export const SYSCALL_EVENT_LIMIT = 1000;
export const MAX_PROCESS_ID = 2_147_483_647;
export const MAX_UID = 4_294_967_295;

export type Scope = "tgid" | "tid" | "uid";
export type Phase = "enter" | "exit" | "both";
export type BacktraceMode = "fp" | "dwarf";

export type SyscallAvailability = "available" | "kernel_disabled" | "arch_unsupported";

export interface SyscallCatalogEntry {
  nr: number;
  name: string;
  availability: SyscallAvailability;
  /** The KPM renders this syscall's arguments symbolically. */
  schema: boolean;
  /** Only the enter phase is observable (exit / exit_group). */
  enterOnly: boolean;
}
export interface SyscallRule { syscallNr: number; name?: string; scope: Scope; targetId: number; phase: Phase; backtrace?: boolean; backtraceStackSize?: number; }
export interface SyscallListener { state: string; error: string | null; }
export interface SyscallCapture { argIndex?: number; kind?: string; schema?: string; phase?: string; value?: string | null; status?: string; }
export interface SyscallEvent {
  type?: string;
  displayId?: number;
  seq?: string;
  entrySeq?: string;
  exitSeq?: string;
  callId?: string;
  phase?: "enter" | "exit" | "complete";
  tid?: number;
  tgid?: number;
  syscallNr?: number;
  name?: string;
  comm?: string;
  scope?: Scope;
  targetId?: number;
  argsBefore?: string[];
  args?: string[];
  captures?: SyscallCapture[];
  returnBefore?: string | null;
  return?: string | null;
  afterDropped?: boolean;
  dropped?: string;
  relayDropped?: string;
  backtraceMode?: BacktraceMode | null;
  backtraceNote?: string | null;
  backtraceFrames?: Array<{ index?: number; pc?: string; label?: string; module?: string | null; offset?: string | null }>;
}

export interface InstalledApp { packageName: string; label: string; uid: number; isSystem: boolean; }

export interface SyscallState {
  rules: SyscallRule[];
  listener: SyscallListener;
  catalog: SyscallCatalogEntry[];
  events: SyscallEvent[];
  pending: Record<string, SyscallEvent>;
  nextDisplayId: number;
  selectedSyscallNr: number | null;
  eventSearchQuery: string;
  backtraceMode: BacktraceMode;
  connection: "connecting" | "open" | "closed" | "error";
  error: string | null;
}

export const initialSyscallState = (): SyscallState => ({
  rules: [],
  listener: { state: "starting", error: null },
  catalog: [],
  events: [],
  pending: {},
  nextDisplayId: 1,
  selectedSyscallNr: null,
  eventSearchQuery: "",
  backtraceMode: "fp",
  connection: "connecting",
  error: null,
});

export function reduceSyscall(state: SyscallState, message: Record<string, unknown>): SyscallState {
  if (message.type === "state") {
    const rules = Array.isArray(message.rules) ? message.rules as SyscallRule[] : [];
    const catalog = message.syscallCatalog === undefined ? state.catalog : normalizeSyscallCatalog(message.syscallCatalog);
    const requested = state.selectedSyscallNr;
    const selected = observedSyscallsFromRules(rules).some((entry) => entry.nr === requested) ? requested : observedSyscallsFromRules(rules)[0]?.nr ?? null;
    return {
      ...state,
      rules,
      listener: normalizeListener(message.listener, state.listener),
      catalog,
      selectedSyscallNr: selected,
      backtraceMode: normalizeBacktraceMode(message.backtraceMode ?? state.backtraceMode),
      error: null,
    };
  }
  if (message.type === "event") {
    const incoming = message as SyscallEvent;
    const phase = incoming.phase === "enter" || incoming.phase === "exit" ? incoming.phase : "enter";
    const key = callKey(incoming);
    if (phase === "exit" && key && state.pending[key]) {
      const entry = state.pending[key];
      const combined: SyscallEvent = {
        ...incoming,
        displayId: entry.displayId ?? state.nextDisplayId,
        phase: "complete",
        entrySeq: entry.seq,
        exitSeq: incoming.seq,
        argsBefore: entry.argsBefore ?? incoming.argsBefore,
        args: entry.args ?? incoming.args,
        captures: [...(entry.captures ?? []), ...(incoming.captures ?? [])],
      };
      const pending = { ...state.pending };
      delete pending[key];
      return {
        ...state,
        pending,
        events: [combined, ...state.events.filter((event) => event.displayId !== entry.displayId)].slice(0, SYSCALL_EVENT_LIMIT),
      };
    }
    const displayId = state.nextDisplayId;
    const pending = { ...state.pending };
    if (phase === "enter" && key) pending[key] = { ...incoming, displayId };
    return {
      ...state,
      pending,
      nextDisplayId: displayId + 1,
      events: [{ ...incoming, phase, displayId }, ...state.events].slice(0, SYSCALL_EVENT_LIMIT),
    };
  }
  if (message.type === "ack" && message.ok === false) return { ...state, error: String(message.error || "操作失敗") };
  return state;
}

function callKey(event: SyscallEvent): string | null {
  if (!event.callId || event.tid == null) return null;
  return `${event.tid}:${event.callId}`;
}

function normalizeListener(value: unknown, fallback: SyscallListener): SyscallListener {
  if (!value || typeof value !== "object") return fallback;
  const listener = value as Record<string, unknown>;
  return { state: String(listener.state ?? fallback.state), error: listener.error == null ? null : String(listener.error) };
}

export function setSyscallConnection(state: SyscallState, connection: SyscallState["connection"]): SyscallState { return { ...state, connection }; }
export function clearSyscallEvents(state: SyscallState): SyscallState { return { ...state, events: [], pending: {}, nextDisplayId: 1 }; }
export function setSyscallError(state: SyscallState, error: string | null): SyscallState { return { ...state, error }; }
export function selectSyscall(state: SyscallState, value: string | number | null): SyscallState { return { ...state, selectedSyscallNr: value == null || value === "" ? null : Number(value) }; }
export function setSyscallSearch(state: SyscallState, value: string): SyscallState { return { ...state, eventSearchQuery: value }; }

/** arm64 native syscall numbers the picker offers. */
export const SYSCALL_SELECTOR_FIRST = 1;
export const SYSCALL_SELECTOR_LAST = 291;

/**
 * Normalize the server's catalog. The server is authoritative for names and
 * availability, so an entry it did not describe is dropped rather than guessed:
 * a stale local table would silently mislabel syscalls after a KPM update.
 */
export function normalizeSyscallCatalog(value: unknown): SyscallCatalogEntry[] {
  const source: unknown[] = Array.isArray(value) ? value : (value && typeof value === "object" && Array.isArray((value as Record<string, unknown>).syscallCatalog) ? (value as Record<string, unknown>).syscallCatalog as unknown[] : []);
  const map = new Map<number, SyscallCatalogEntry>();
  for (const item of source) {
    if (!item || typeof item !== "object") continue;
    const raw = item as Record<string, unknown>;
    const nr = Number(raw.nr);
    if (!Number.isInteger(nr) || nr < SYSCALL_SELECTOR_FIRST || nr > SYSCALL_SELECTOR_LAST) continue;
    const name = String(raw.name ?? "").trim() || syscallName(nr);
    const availability = isAvailability(raw.availability) ? raw.availability : "available";
    map.set(nr, { nr, name, availability, schema: Boolean(raw.schema), enterOnly: Boolean(raw.enterOnly) });
  }
  return [...map.values()].sort((a, b) => a.nr - b.nr);
}

function isAvailability(value: unknown): value is SyscallAvailability {
  return value === "available" || value === "kernel_disabled" || value === "arch_unsupported";
}

/** A rule's own name, or its number when the server has not named it. */
export function observedSyscallsFromRules(rules: SyscallRule[]): SyscallCatalogEntry[] {
  const map = new Map<number, SyscallCatalogEntry>();
  rules.forEach((rule) => {
    const nr = Number(rule.syscallNr);
    if (Number.isInteger(nr)) map.set(nr, { nr, name: rule.name || syscallName(nr), availability: "available", schema: false, enterOnly: nr === 93 || nr === 94 });
  });
  return [...map.values()].sort((a, b) => a.nr - b.nr);
}

export function filterSyscallEvents(state: SyscallState): SyscallEvent[] {
  const selected = state.selectedSyscallNr;
  if (selected == null || !Number.isSafeInteger(selected)) return [];
  return filterEventsBySearch(
    state.events.filter((event) => Number(event.syscallNr) === selected),
    state.eventSearchQuery,
  );
}

/** Search every scalar value in an event, including nested captures/backtraces. */
export function filterEventsBySearch(events: SyscallEvent[], query: unknown): SyscallEvent[] {
  const terms = String(query ?? "")
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  const source = Array.isArray(events) ? events : [];
  if (terms.length === 0) return source;

  return source.filter((event) => {
    const values: string[] = [];
    const collect = (value: unknown): void => {
      if (value === null || value === undefined) return;
      if (Array.isArray(value)) {
        value.forEach(collect);
        return;
      }
      if (typeof value === "object") {
        Object.values(value as Record<string, unknown>).forEach(collect);
        return;
      }
      values.push(String(value));
    };
    collect(event);
    values.push(syscallName(event.syscallNr), event.name || "");
    const searchableText = values.filter(Boolean).join(" ").toLowerCase();
    return terms.every((term) => searchableText.includes(term));
  });
}

export function normalizeInstalledApps(value: unknown): InstalledApp[] {
  const source: unknown[] = Array.isArray(value) ? value : (value && typeof value === "object" && Array.isArray((value as Record<string, unknown>).apps) ? (value as Record<string, unknown>).apps as unknown[] : []);
  const map = new Map<string, InstalledApp>();
  for (const item of source) {
    if (!item || typeof item !== "object") continue;
    const raw = item as Record<string, unknown>;
    const packageName = String(raw.packageName ?? "").trim();
    const uid = Number(raw.uid);
    if (!packageName || !Number.isInteger(uid) || uid <= 0 || uid > MAX_UID || map.has(packageName)) continue;
    map.set(packageName, { packageName, label: String(raw.label ?? packageName).trim() || packageName, uid, isSystem: Boolean(raw.isSystem) });
  }
  return [...map.values()].sort((a, b) => a.packageName.localeCompare(b.packageName));
}

export function selectedPackageUid(apps: InstalledApp[], packageName: string): number {
  const app = apps.find((item) => item.packageName === packageName);
  if (!app) throw new Error("找不到選取的 App");
  return app.uid;
}

export function createSyscallObserveCommand(values: { syscallNr: unknown; scope: Scope; targetId: unknown; phase: Phase; captureBacktrace: boolean; backtraceStackSize: unknown }): Record<string, unknown> {
  const syscallNr = parseInteger(values.syscallNr, "Syscall 編號");
  if (syscallNr < SYSCALL_SELECTOR_FIRST || syscallNr > SYSCALL_SELECTOR_LAST) throw new Error(`Syscall 編號必須介於 ${SYSCALL_SELECTOR_FIRST} 至 ${SYSCALL_SELECTOR_LAST}`);
  const targetId = parseInteger(values.targetId, "目標 ID");
  if (targetId <= 0 || targetId > (values.scope === "uid" ? MAX_UID : MAX_PROCESS_ID)) throw new Error("目標 ID 超出範圍");
  const command: Record<string, unknown> = { type: "observe", syscallNr, scope: values.scope, targetId, phase: values.phase, backtrace: Boolean(values.captureBacktrace) };
  if (values.captureBacktrace) {
    const size = values.backtraceStackSize === "" ? 4096 : parseInteger(values.backtraceStackSize, "Stack size");
    if (size < 0 || size > 65536) throw new Error("Stack size 必須介於 0 至 65536");
    command.backtraceStackSize = size;
  }
  return command;
}

export function createSyscallUnobserveCommand(rule: SyscallRule): Record<string, unknown> {
  return { type: "unobserve", syscallNr: Number(rule.syscallNr), scope: rule.scope, targetId: Number(rule.targetId), phase: rule.phase };
}
export function createSyscallConfigCommand(mode: BacktraceMode): Record<string, unknown> { return { type: "config", backtraceMode: normalizeBacktraceMode(mode) }; }
export const createRestartListenerCommand = () => ({ type: "restartListener" });

export function normalizeBacktraceMode(value: unknown): BacktraceMode { const mode = String(value ?? "fp").toLowerCase(); if (mode !== "fp" && mode !== "dwarf") throw new Error("Backtrace mode must be fp or dwarf"); return mode; }
function parseInteger(value: unknown, label: string): number { const number = Number(value); if (!Number.isInteger(number)) throw new Error(`${label} 必須是整數`); return number; }

/**
 * Fallback names for rules and events the server has not described yet.
 * Mirrors Arm64SyscallCatalog; arm64 numbers differ from x86 (66 is writev).
 */
const names: Record<number, string> = {
  1: "io_destroy",
  2: "io_submit",
  3: "io_cancel",
  4: "io_getevents",
  5: "setxattr",
  6: "lsetxattr",
  7: "fsetxattr",
  8: "getxattr",
  9: "lgetxattr",
  10: "fgetxattr",
  11: "listxattr",
  12: "llistxattr",
  13: "flistxattr",
  14: "removexattr",
  15: "lremovexattr",
  16: "fremovexattr",
  17: "getcwd",
  18: "lookup_dcookie",
  19: "eventfd2",
  20: "epoll_create1",
  21: "epoll_ctl",
  22: "epoll_pwait",
  23: "dup",
  24: "dup3",
  25: "fcntl",
  26: "inotify_init1",
  27: "inotify_add_watch",
  28: "inotify_rm_watch",
  29: "ioctl",
  30: "ioprio_set",
  31: "ioprio_get",
  32: "flock",
  33: "mknodat",
  34: "mkdirat",
  35: "unlinkat",
  36: "symlinkat",
  37: "linkat",
  38: "renameat",
  39: "umount2",
  40: "mount",
  41: "pivot_root",
  42: "nfsservctl",
  43: "statfs",
  44: "fstatfs",
  45: "truncate",
  46: "ftruncate",
  47: "fallocate",
  48: "faccessat",
  49: "chdir",
  50: "fchdir",
  51: "chroot",
  52: "fchmod",
  53: "fchmodat",
  54: "fchownat",
  55: "fchown",
  56: "openat",
  57: "close",
  58: "vhangup",
  59: "pipe2",
  60: "quotactl",
  61: "getdents64",
  62: "lseek",
  63: "read",
  64: "write",
  65: "readv",
  66: "writev",
  67: "pread64",
  68: "pwrite64",
  69: "preadv",
  70: "pwritev",
  71: "sendfile",
  72: "pselect6",
  73: "ppoll",
  74: "signalfd4",
  75: "vmsplice",
  76: "splice",
  77: "tee",
  78: "readlinkat",
  79: "newfstatat",
  80: "fstat",
  81: "sync",
  82: "fsync",
  83: "fdatasync",
  84: "sync_file_range",
  85: "timerfd_create",
  86: "timerfd_settime",
  87: "timerfd_gettime",
  88: "utimensat",
  89: "acct",
  90: "capget",
  91: "capset",
  92: "personality",
  93: "exit",
  94: "exit_group",
  95: "waitid",
  96: "set_tid_address",
  97: "unshare",
  98: "futex",
  99: "set_robust_list",
  100: "get_robust_list",
  101: "nanosleep",
  102: "getitimer",
  103: "setitimer",
  104: "kexec_load",
  105: "init_module",
  106: "delete_module",
  107: "timer_create",
  108: "timer_gettime",
  109: "timer_getoverrun",
  110: "timer_settime",
  111: "timer_delete",
  112: "clock_settime",
  113: "clock_gettime",
  114: "clock_getres",
  115: "clock_nanosleep",
  116: "syslog",
  117: "ptrace",
  118: "sched_setparam",
  119: "sched_setscheduler",
  120: "sched_getscheduler",
  121: "sched_getparam",
  122: "sched_setaffinity",
  123: "sched_getaffinity",
  124: "sched_yield",
  125: "sched_get_priority_max",
  126: "sched_get_priority_min",
  127: "sched_rr_get_interval",
  128: "restart_syscall",
  129: "kill",
  130: "tkill",
  131: "tgkill",
  132: "sigaltstack",
  133: "rt_sigsuspend",
  134: "rt_sigaction",
  135: "rt_sigprocmask",
  136: "rt_sigpending",
  137: "rt_sigtimedwait",
  138: "rt_sigqueueinfo",
  139: "rt_sigreturn",
  140: "setpriority",
  141: "getpriority",
  142: "reboot",
  143: "setregid",
  144: "setgid",
  145: "setreuid",
  146: "setuid",
  147: "setresuid",
  148: "getresuid",
  149: "setresgid",
  150: "getresgid",
  151: "setfsuid",
  152: "setfsgid",
  153: "times",
  154: "setpgid",
  155: "getpgid",
  156: "getsid",
  157: "setsid",
  158: "getgroups",
  159: "setgroups",
  160: "uname",
  161: "sethostname",
  162: "setdomainname",
  163: "getrlimit",
  164: "setrlimit",
  165: "getrusage",
  166: "umask",
  167: "prctl",
  168: "getcpu",
  169: "gettimeofday",
  170: "settimeofday",
  171: "adjtimex",
  172: "getpid",
  173: "getppid",
  174: "getuid",
  175: "geteuid",
  176: "getgid",
  177: "getegid",
  178: "gettid",
  179: "sysinfo",
  180: "mq_open",
  181: "mq_unlink",
  182: "mq_timedsend",
  183: "mq_timedreceive",
  184: "mq_notify",
  185: "mq_getsetattr",
  186: "msgget",
  187: "msgctl",
  188: "msgrcv",
  189: "msgsnd",
  190: "semget",
  191: "semctl",
  192: "semtimedop",
  193: "semop",
  194: "shmget",
  195: "shmctl",
  196: "shmat",
  197: "shmdt",
  198: "socket",
  199: "socketpair",
  200: "bind",
  201: "listen",
  202: "accept",
  203: "connect",
  204: "getsockname",
  205: "getpeername",
  206: "sendto",
  207: "recvfrom",
  208: "setsockopt",
  209: "getsockopt",
  210: "shutdown",
  211: "sendmsg",
  212: "recvmsg",
  213: "readahead",
  214: "brk",
  215: "munmap",
  216: "mremap",
  217: "add_key",
  218: "request_key",
  219: "keyctl",
  220: "clone",
  221: "execve",
  222: "mmap",
  223: "fadvise64",
  224: "swapon",
  225: "swapoff",
  226: "mprotect",
  227: "msync",
  228: "mlock",
  229: "munlock",
  230: "mlockall",
  231: "munlockall",
  232: "mincore",
  233: "madvise",
  234: "remap_file_pages",
  235: "mbind",
  236: "get_mempolicy",
  237: "set_mempolicy",
  238: "migrate_pages",
  239: "move_pages",
  240: "rt_tgsigqueueinfo",
  241: "perf_event_open",
  242: "accept4",
  243: "recvmmsg",
  244: "arch_specific_syscall",
  245: "(reserved)",
  246: "(reserved)",
  247: "(reserved)",
  248: "(reserved)",
  249: "(reserved)",
  250: "(reserved)",
  251: "(reserved)",
  252: "(reserved)",
  253: "(reserved)",
  254: "(reserved)",
  255: "(reserved)",
  256: "(reserved)",
  257: "(reserved)",
  258: "(reserved)",
  259: "(reserved)",
  260: "wait4",
  261: "prlimit64",
  262: "fanotify_init",
  263: "fanotify_mark",
  264: "name_to_handle_at",
  265: "open_by_handle_at",
  266: "clock_adjtime",
  267: "syncfs",
  268: "setns",
  269: "sendmmsg",
  270: "process_vm_readv",
  271: "process_vm_writev",
  272: "kcmp",
  273: "finit_module",
  274: "sched_setattr",
  275: "sched_getattr",
  276: "renameat2",
  277: "seccomp",
  278: "getrandom",
  279: "memfd_create",
  280: "bpf",
  281: "execveat",
  282: "userfaultfd",
  283: "membarrier",
  284: "mlock2",
  285: "copy_file_range",
  286: "preadv2",
  287: "pwritev2",
  288: "pkey_mprotect",
  289: "pkey_alloc",
  290: "pkey_free",
  291: "statx",
};
export function syscallName(nr: unknown): string { return names[Number(nr)] ?? `syscall_${nr}`; }
