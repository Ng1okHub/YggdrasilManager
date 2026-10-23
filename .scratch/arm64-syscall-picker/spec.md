# arm64 syscall picker

## Goal

The syscall observe picker listed every number `0..459` as `syscall_N`. Restrict it
to the arm64 native range, show each syscall's real name, and mark the numbers that
cannot produce events so they are not chosen by mistake. Add search.

## Range

The picker offers `1..291` (`Arm64SyscallCatalog.SELECTOR_FIRST..SELECTOR_LAST`).

`0` is `io_setup`, and `292+` / `424+` are real arm64 syscalls, but the request was
to keep `1..291`, so both are excluded. `Arm64SyscallCatalog.nameFor` still resolves
them for rules and events that predate this change.

## Names

Names come from the arm64 `asm-generic/unistd.h` ABI (`__NR_*`), which is what
strace and libc report. The kernel's internal `sys_*` symbols differ for a few
numbers and are deliberately not used:

| nr | ABI name | kernel symbol |
|----|----------|---------------|
| 39 | `umount2` | `sys_umount` |
| 71 | `sendfile` | `sys_sendfile64` |
| 80 | `fstat` | `sys_newfstat` |
| 92 | `personality` | `sys_arm64_personality` |
| 160 | `uname` | `sys_newuname` |
| 223 | `fadvise64` | `sys_fadvise64_64` |

arm64 numbering also differs from x86: `63` is `read`, `66` is `writev`, `62` is
`lseek`. The old frontend fallback table had `66: "lseek"` — inherited from x86 — so
it is replaced rather than extended.

## Availability

`SyscallAvailability` has three values. Two of them mean "this will never produce an
event", but for different reasons, so the picker labels them apart:

- `AVAILABLE` — the kernel implements it.
- `ARCH_UNSUPPORTED` — no arm64 implementation exists: `42` (`nfsservctl`, removed
  from Linux), `244` (`arch_specific_syscall`, a marker) and the reserved block
  `245..259`. 17 numbers.
- `KERNEL_DISABLED` — defined by the ABI but compiled out of the running kernel.
  32 numbers:

  | numbers | syscall | missing config |
  |---------|---------|----------------|
  | 89 | `acct` | `CONFIG_BSD_PROCESS_ACCT` |
  | 104 | `kexec_load` | `CONFIG_KEXEC` |
  | 180..185 | `mq_*` | `CONFIG_POSIX_MQUEUE` |
  | 186..197 | SysV IPC | `CONFIG_SYSVIPC` |
  | 235..239 | `mbind` .. | `CONFIG_NUMA` |
  | 262, 263 | `fanotify_*` | `CONFIG_FANOTIFY` |
  | 264, 265 | `name_to_handle_at`, `open_by_handle_at` | `CONFIG_FHANDLE` |
  | 288..290 | `pkey_*` | no arm64 pkeys support |

### How the two lists were derived

Measured, not assumed. A fork-per-syscall probe called every number in `1..291` with
all-zero arguments and recorded `ENOSYS`:

```c
pid_t pid = fork();
if (pid == 0) { alarm(2); syscall(nr, 0,0,0,0,0,0); _exit(errno == ENOSYS ? 1 : 0); }
```

Result on arm64 kernel `5.10.249-android13-4` (aarch64, Android 13 GKI): 49 numbers
returned `ENOSYS`; the remaining 242 are implemented. Each of the 32 in
`KERNEL_DISABLED` is explained by a `CONFIG_*` that `/proc/config.gz` showed as not
set, which is why they are called out as kernel-build dependent rather than permanent.

A rule on a `KERNEL_DISABLED` syscall installs but never fires, so a different kernel
build could make those numbers usable again. `ARCH_UNSUPPORTED` numbers stay
unusable on every arm64 kernel.

## Auto argument parsing

The KPM's `arm64_syscall_schemas` table decides which syscalls get symbolic arguments
and struct decoding. The picker marks those rows with an `args` tag. The list is not
duplicated in Kotlin or TypeScript: `YggdrasilNative.syscallCaptureSupportedNumbers()`
already reads the table in `yggdrasil_syscall_format.h` through JNI, so there is one
source of truth.

The 20 schema numbers are 34, 35, 36, 37, 38, 43, 48, 49, 56, 63, 64, 67, 68, 78, 79,
113, 200, 203, 221 and 291.

## Enter-only syscalls

`93` (`exit`) and `94` (`exit_group`) never return, so the server rejects exit-phase
rules for them (`SyscallObserveRule.EXIT_ONLY_UNSUPPORTED`). The picker mirrors that:
selecting one locks the phase control to `enter`.

## Where the data lives

`Arm64SyscallCatalog` (`data/model/Arm64SyscallCatalog.kt`) is authoritative. The
server sends one catalog entry per syscall — `nr`, `name`, `availability`, `schema`,
`enterOnly` — in the `state` message, and the web bundle renders whatever it receives.
The bundle is packaged inside the APK, so it and the server always ship together; the
frontend keeps only a name table for rules and events that arrive without a catalog.
