#include <jni.h>
#include <sys/prctl.h>
#include <errno.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cinttypes>
#include <cstdio>
#include <string>
#include <vector>

#include "yggdrasil.h"
#include "ygg_backtrace.h"

// Diagnostic logging for the syscall-observe listener. Filter with:
//   adb logcat -s YggSyscall
// These logs trace the errno-5 (EIO) listener-stop path; see analysis notes.
#define YGG_SYSCALL_LOG_TAG "YggSyscall"
#define YGG_SLOGI(...) \
    __android_log_print(ANDROID_LOG_INFO, YGG_SYSCALL_LOG_TAG, __VA_ARGS__)
#define YGG_SLOGW(...) \
    __android_log_print(ANDROID_LOG_WARN, YGG_SYSCALL_LOG_TAG, __VA_ARGS__)
#define YGG_SLOGE(...) \
    __android_log_print(ANDROID_LOG_ERROR, YGG_SYSCALL_LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;
static jobject g_callback_ref = nullptr;
static jmethodID g_on_event_mid = nullptr;
static pthread_t g_listen_thread = 0;
static std::atomic<bool> g_stop{false};
static pthread_mutex_t g_listen_mutex = PTHREAD_MUTEX_INITIALIZER;

static jobject g_syscall_callback_ref = nullptr;
static jmethodID g_on_syscall_event_mid = nullptr;
static jmethodID g_on_syscall_listener_failure_mid = nullptr;
static pthread_t g_syscall_listen_thread = 0;
static std::atomic<bool> g_syscall_stop{false};
static pthread_mutex_t g_syscall_listen_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_syscall_start_cond = PTHREAD_COND_INITIALIZER;
static bool g_syscall_start_pending = false;
static int g_syscall_start_result = -EIO;

static_assert(sizeof(struct ygg_syscall_rule_request) ==
                  YGG_SYSCALL_RULE_REQUEST_SIZE,
              "unexpected syscall rule ABI layout");
static_assert(sizeof(struct ygg_syscall_event) ==
                  YGG_SYSCALL_EVENT_HEADER_SIZE,
              "unexpected syscall event ABI layout");
static_assert(sizeof(struct ygg_syscall_capture_tlv) ==
                  YGG_SYSCALL_CAPTURE_TLV_SIZE,
              "unexpected syscall capture ABI layout");
static_assert(sizeof(struct ygg_syscall_event_record) ==
                  YGG_SYSCALL_EVENT_SIZE_MAX,
              "unexpected syscall event record ABI layout");
static_assert(sizeof(struct ygg_syscall_read_event_request) ==
                  YGG_SYSCALL_READ_REQUEST_SIZE,
              "unexpected syscall read ABI layout");

/* Keep this table in lockstep with yggdrasil_client.cpp's automatic schema. */
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
    uint8_t capture_count;
    syscall_capture_template captures[2];
};

static const arm64_syscall_schema kArm64SyscallSchemas[] = {
    { 56, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 63, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG_RETURN_MIN, 2,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 64, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 67, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG_RETURN_MIN, 2,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 68, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_BYTES,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 113, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY,
          YGG_SYSCALL_SCHEMA_TIMESPEC64, 16, 16 },
    } },
    { 200, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0,
          YGG_SYSCALL_SCHEMA_SOCKADDR, 0, 128 },
    } },
    { 203, 1, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_ARG, 2, 0,
          YGG_SYSCALL_SCHEMA_SOCKADDR, 0, 128 },
    } },
    { 221, 1, {
        { 0, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
    } },
    { 291, 2, {
        { 1, YGG_SYSCALL_CAPTURE_PHASE_ENTER, YGG_SYSCALL_CAPTURE_CSTRING,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0, 0, YGG_SYSCALL_SCHEMA_NONE,
          0, YGG_SYSCALL_CAPTURE_BYTES_MAX },
        { 4, YGG_SYSCALL_CAPTURE_PHASE_EXIT, YGG_SYSCALL_CAPTURE_STRUCT,
          YGG_SYSCALL_CAPTURE_LENGTH_FIXED, 0,
          YGG_SYSCALL_CAPTURE_F_SUCCESS_ONLY, YGG_SYSCALL_SCHEMA_STATX,
          256, 256 },
    } },
};

static const arm64_syscall_schema *find_arm64_syscall_schema(uint32_t nr) {
    for (const auto &schema : kArm64SyscallSchemas) {
        if (schema.nr == nr) return &schema;
    }
    return nullptr;
}

static void populate_auto_capture_descriptors(
        uint32_t syscall_nr, uint32_t trace_flags,
        ygg_syscall_rule_request *request) {
    if (!request) return;
    request->capture_count = 0;
    request->capture_total_limit = 0;

    const arm64_syscall_schema *schema = find_arm64_syscall_schema(syscall_nr);
    if (!schema) return;
    uint8_t enabled_phases = 0;
    if (trace_flags & YGG_SYSCALL_F_TRACE_ENTER)
        enabled_phases |= YGG_SYSCALL_CAPTURE_PHASE_ENTER;
    if (trace_flags & YGG_SYSCALL_F_TRACE_EXIT)
        enabled_phases |= YGG_SYSCALL_CAPTURE_PHASE_EXIT;

    for (uint8_t i = 0; i < schema->capture_count; ++i) {
        const syscall_capture_template &source = schema->captures[i];
        if (!(source.phase_mask & enabled_phases) ||
            request->capture_count >= YGG_SYSCALL_CAPTURE_COUNT_MAX) {
            continue;
        }
        ygg_syscall_capture_desc &dest =
                request->captures[request->capture_count++];
        dest.arg_index = source.arg_index;
        dest.phase_mask = source.phase_mask & enabled_phases;
        dest.kind = source.kind;
        dest.length_source = source.length_source;
        dest.length_arg = source.length_arg;
        dest.pointer_source = YGG_SYSCALL_CAPTURE_POINTER_EFFECTIVE;
        dest.flags = source.flags;
        dest.schema_id = source.schema_id;
        dest.fixed_length = source.fixed_length;
        dest.max_length = std::min(source.max_length,
                                   YGG_SYSCALL_CAPTURE_PAYLOAD_MAX);
    }
    if (request->capture_count > 0)
        request->capture_total_limit = YGG_SYSCALL_CAPTURE_PAYLOAD_MAX;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    // pid helps distinguish the app process from the libsu root process, since
    // the listener thread runs in root and must be able to attach to the JVM.
    YGG_SLOGI("JNI_OnLoad: g_jvm cached (pid=%d)", getpid());
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_checkAvailable(JNIEnv *, jobject) {
    return prctl(PR_YGGDRASIL_AVAILABLE, 0, 0, 0, 0) >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_getVersion(JNIEnv *, jobject) {
    return static_cast<jlong>(prctl(PR_YGGDRASIL_GET_VERSION, 0, 0, 0, 0));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_syscallCaptureSupportedNumbers(
        JNIEnv *env, jobject) {
    std::vector<jint> numbers;
    numbers.reserve(sizeof(kArm64SyscallSchemas) / sizeof(kArm64SyscallSchemas[0]));
    for (const auto &schema : kArm64SyscallSchemas) {
        numbers.push_back(static_cast<jint>(schema.nr));
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(numbers.size()));
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(numbers.size()), numbers.data());
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_hwbpObserve(
        JNIEnv *, jobject, jint pid, jlong targetAddr,
        jint mode, jint len, jint flags, jint stackSize) {
    struct ygg_hwbp_observe_request req = {};
    req.target_addr  = (uint64_t)targetAddr;
    req.target_pid   = (uint32_t)pid;
    req.mode         = (uint32_t)mode;
    req.len          = (uint32_t)len;
    req.flags        = (uint32_t)flags;
    req.stack_size   = (uint32_t)stackSize;
    long ret = prctl(PR_YGGDRASIL_HWBP_OBSERVE, &req, 0, 0, 0);
    return (jint)(ret < 0 ? -errno : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_hwbpUnobserve(
        JNIEnv *, jobject, jint pid, jlong targetAddr) {
    struct ygg_hwbp_observe_request req = {};
    req.target_addr = (uint64_t)targetAddr;
    req.target_pid  = (uint32_t)pid;
    long ret = prctl(PR_YGGDRASIL_HWBP_UNOBSERVE, &req, 0, 0, 0);
    return (jint)(ret < 0 ? -errno : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_hideDbg(
        JNIEnv *, jobject, jboolean enable, jboolean hasUid, jint uid) {
    struct ygg_hide_dbg_request req = {};
    req.enable = enable ? 1U : 0U;
    if (hasUid) {
        req.flags = YGG_HIDE_DBG_F_TARGET_UID;
        req.uid   = (uint32_t)uid;
    }
    long ret = prctl(PR_YGGDRASIL_HIDE_DBG, &req, 0, 0, 0);
    return (jint)(ret < 0 ? -errno : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_syscallConfigure(
        JNIEnv *, jobject, jint op, jint flags, jint syscallNr, jlong targetId) {
    struct ygg_syscall_rule_request req = {};
    const uint32_t selector_flags = YGG_SYSCALL_F_SINGLE_THREAD |
                                    YGG_SYSCALL_F_TARGET_UID;
    const uint32_t observe_flags = selector_flags |
                                   YGG_SYSCALL_F_TRACE_ENTER |
                                   YGG_SYSCALL_F_TRACE_EXIT;
    const uint32_t request_flags = (uint32_t)flags;

    if (syscallNr < 0 || (uint32_t)syscallNr >= YGG_ARM64_SYSCALL_COUNT) {
        return -EINVAL;
    }
    if (op != YGG_SYSCALL_RULE_SET && op != YGG_SYSCALL_RULE_DELETE) {
        return -EINVAL;
    }
    if (request_flags & YGG_SYSCALL_F_TARGET_UID) {
        if (targetId < 0 || targetId > (jlong)UINT32_MAX) return -EINVAL;
        req.target_uid = (uint32_t)targetId;
    } else {
        if (targetId < 0 || targetId > (jlong)INT32_MAX ||
            ((request_flags & YGG_SYSCALL_F_SINGLE_THREAD) && !targetId)) {
            return -EINVAL;
        }
        req.target_pid = (uint32_t)targetId;
    }

    req.size = sizeof(req);
    req.version = YGG_SYSCALL_ABI_VERSION;
    req.op = (uint32_t)op;
    req.syscall_nr = (uint32_t)syscallNr;
    if (op == YGG_SYSCALL_RULE_SET) {
        if (request_flags & ~observe_flags ||
            !(request_flags &
              (YGG_SYSCALL_F_TRACE_ENTER | YGG_SYSCALL_F_TRACE_EXIT))) {
            return -EINVAL;
        }
        req.flags = request_flags;
        populate_auto_capture_descriptors(req.syscall_nr, req.flags, &req);
    } else {
        if (request_flags & ~selector_flags) {
            return -EINVAL;
        }
        req.flags = request_flags;
    }

    long ret = prctl(PR_YGGDRASIL_SYSCALL_CONFIG, &req, 0, 0, 0);
    if (ret < 0 && errno == EOPNOTSUPP && req.capture_count > 0) {
        YGG_SLOGW("capture unavailable for syscall %u; retrying numeric-only rule",
                  req.syscall_nr);
        req.capture_count = 0;
        req.capture_total_limit = 0;
        memset(req.captures, 0, sizeof(req.captures));
        ret = prctl(PR_YGGDRASIL_SYSCALL_CONFIG, &req, 0, 0, 0);
    }
    return (jint)(ret < 0 ? -errno : 0);
}

static void unregister_syscall_listener() {
    struct ygg_syscall_read_event_request req = {};

    req.size = sizeof(req);
    req.version = YGG_SYSCALL_ABI_VERSION;
    req.flags = YGG_SYSCALL_READ_F_UNREGISTER;
    prctl(PR_YGGDRASIL_SYSCALL_READ_EVENT, &req, 0, 0, 0);
}

static bool is_valid_syscall_event(
        const struct ygg_syscall_event_record *record, long result) {
    static constexpr uint32_t capture_status_mask =
            YGG_SYSCALL_CAPTURE_STATUS_NULL |
            YGG_SYSCALL_CAPTURE_STATUS_FAULT |
            YGG_SYSCALL_CAPTURE_STATUS_TRUNCATED |
            YGG_SYSCALL_CAPTURE_STATUS_SKIPPED |
            YGG_SYSCALL_CAPTURE_STATUS_BUDGET;
    if (!record || result < 0 || (size_t)result < sizeof(record->event) ||
        (size_t)result > sizeof(*record)) {
        return false;
    }
    const struct ygg_syscall_event *event = &record->event;
    if (event->magic != YGG_SYSCALL_EVENT_MAGIC ||
        event->version != YGG_SYSCALL_EVENT_VERSION ||
        event->header_size != sizeof(*event) ||
        event->total_size != (uint32_t)result ||
        event->payload_size !=
            (uint32_t)((size_t)result - sizeof(*event)) ||
        event->payload_size > YGG_SYSCALL_CAPTURE_PAYLOAD_MAX ||
        event->capture_count > YGG_SYSCALL_CAPTURE_COUNT_MAX ||
        event->phase != YGG_SYSCALL_PHASE_ENTER &&
            event->phase != YGG_SYSCALL_PHASE_EXIT ||
        event->abi != YGG_SYSCALL_EVENT_ABI_ARM64 || !event->call_id ||
        event->syscall_nr >= YGG_ARM64_SYSCALL_COUNT ||
        (!!event->capture_count !=
            !!(event->event_flags & YGG_SYSCALL_EVENT_F_HAS_CAPTURES))) {
        return false;
    }

    const uint8_t *cursor =
            reinterpret_cast<const uint8_t *>(record) + event->header_size;
    const uint8_t *end = cursor + event->payload_size;
    bool has_partial = false;
    for (uint32_t i = 0; i < event->capture_count; ++i) {
        struct ygg_syscall_capture_tlv capture = {};
        const size_t remaining = static_cast<size_t>(end - cursor);
        if (remaining < sizeof(capture)) return false;
        memcpy(&capture, cursor, sizeof(capture));
        if (capture.size < sizeof(capture) || capture.size > remaining ||
            (capture.size & 7U)) {
            return false;
        }
        const size_t data_capacity = capture.size - sizeof(capture);
        if (capture.captured_length > data_capacity ||
            capture.captured_length > capture.original_length ||
            capture.size !=
                ((sizeof(capture) + capture.captured_length + 7U) & ~7U) ||
            capture.arg_index >= YGG_SYSCALL_ARG_COUNT ||
            (capture.kind != YGG_SYSCALL_CAPTURE_CSTRING &&
             capture.kind != YGG_SYSCALL_CAPTURE_BYTES &&
             capture.kind != YGG_SYSCALL_CAPTURE_STRUCT) ||
            capture.schema_id > YGG_SYSCALL_SCHEMA_SOCKADDR ||
            (capture.schema_id != YGG_SYSCALL_SCHEMA_NONE &&
             capture.kind != YGG_SYSCALL_CAPTURE_STRUCT) ||
            capture.phase != event->phase ||
            (capture.pointer_source != YGG_SYSCALL_CAPTURE_POINTER_ORIGINAL &&
             capture.pointer_source != YGG_SYSCALL_CAPTURE_POINTER_EFFECTIVE) ||
            (capture.status & ~capture_status_mask) || capture.reserved) {
            return false;
        }
        has_partial |= capture.status != 0;
        cursor += capture.size;
    }
    return cursor == end &&
           has_partial ==
               !!(event->event_flags & YGG_SYSCALL_EVENT_F_CAPTURE_PARTIAL);
}

static void signal_syscall_start_result(int result) {
    pthread_mutex_lock(&g_syscall_listen_mutex);
    if (g_syscall_start_pending) {
        g_syscall_start_result = result;
        g_syscall_start_pending = false;
        pthread_cond_broadcast(&g_syscall_start_cond);
    }
    pthread_mutex_unlock(&g_syscall_listen_mutex);
}

static void notify_syscall_listener_failure(JNIEnv *env, int error) {
    if (!env || !g_syscall_callback_ref ||
        !g_on_syscall_listener_failure_mid) {
        YGG_SLOGE("listener failure NOT delivered (errno=%d): env=%p ref=%p mid=%p",
                  -error, (void *)env, (void *)g_syscall_callback_ref,
                  (void *)g_on_syscall_listener_failure_mid);
        return;
    }
    // error is the negated errno (e.g. -EIO); Kotlin negates it back to 5.
    YGG_SLOGE("delivering onListenerFailure to Kotlin: errno=%d", -error);
    env->CallVoidMethod(g_syscall_callback_ref,
                        g_on_syscall_listener_failure_mid,
                        (jint)error);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static void clear_syscall_callback(JNIEnv *env) {
    if (g_syscall_callback_ref) {
        env->DeleteGlobalRef(g_syscall_callback_ref);
        g_syscall_callback_ref = nullptr;
    }
    g_on_syscall_event_mid = nullptr;
    g_on_syscall_listener_failure_mid = nullptr;
}

static void *syscall_listen_thread(void *) {
    JNIEnv *env = nullptr;
    // Candidate (A): AttachCurrentThread failing here is the only in-source EIO
    // that fires at startup. Common in the libsu root process if the native lib
    // was not loaded there (g_jvm==null) or the JVM refuses the attach.
    if (!g_jvm || g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        YGG_SLOGE("listener startup FAILED (errno=EIO/5): AttachCurrentThread "
                  "g_jvm=%p env=%p pid=%d", (void *)g_jvm, (void *)env, getpid());
        g_syscall_stop.store(true);
        signal_syscall_start_result(-EIO);
        return nullptr;
    }
    if (!g_on_syscall_event_mid || !g_on_syscall_listener_failure_mid) {
        YGG_SLOGE("listener startup FAILED (errno=EIO/5): null method id "
                  "event=%p failure=%p", (void *)g_on_syscall_event_mid,
                  (void *)g_on_syscall_listener_failure_mid);
        g_syscall_stop.store(true);
        signal_syscall_start_result(-EIO);
        g_jvm->DetachCurrentThread();
        return nullptr;
    }
    YGG_SLOGI("listener thread attached, entering read loop (pid=%d tid=%d)",
              getpid(), gettid());
    signal_syscall_start_result(0);

    struct ygg_syscall_event_record record = {};
    struct ygg_syscall_read_event_request req = {};
    const struct timespec delay = {0, 100000000L};
    req.size = sizeof(req);
    req.version = YGG_SYSCALL_ABI_VERSION;
    req.buffer = (uint64_t)(uintptr_t)&record;
    req.buffer_size = sizeof(record);
    req.flags = YGG_SYSCALL_READ_F_LISTENER;

    int listener_error = 0;
    while (!g_syscall_stop.load()) {
        memset(&record, 0, sizeof(record));
        const long ret = prctl(PR_YGGDRASIL_SYSCALL_READ_EVENT, &req, 0, 0, 0);
        if (ret < 0) {
            const int read_error = errno;
            // EAGAIN (empty queue) / EBUSY (concurrent read) are normal and
            // happen every poll tick; do not log them or they will flood.
            if (read_error == EAGAIN || read_error == EBUSY) {
                nanosleep(&delay, nullptr);
                continue;
            }
            if (read_error == EINTR) continue;
            // EIO means the kernel skipped one corrupt ring slot and advanced
            // the tail.  Treat it like EAGAIN: log once, then keep reading.
            // (Without the kernel fix the ring would wedge; with it, one
            // corrupt event is dropped and subsequent reads proceed normally.)
            if (read_error == EIO) {
                YGG_SLOGW("read loop: kernel skipped corrupt event (EIO), continuing");
                nanosleep(&delay, nullptr);
                continue;
            }
            // Candidate (B): a fatal read errno. errno=5 here means the kernel
            // read-event guard rejected the queued event's total_size, which
            // points at a stale/mismatched on-device KPM binary.
            YGG_SLOGE("read loop FATAL: prctl errno=%d -> stopping listener",
                      read_error);
            listener_error = -read_error;
            break;
        }
        if (!is_valid_syscall_event(&record, ret)) {
            // ABI mismatch between this native lib and the on-device KPM.
            // Dump the header so the offending field is visible in logcat.
            const struct ygg_syscall_event *ev = &record.event;
            YGG_SLOGE("read loop INVALID event (errno=EPROTO/71): ret=%ld "
                      "magic=0x%08x(exp 0x%08x) version=%u(exp %u) "
                      "header_size=%u(exp %zu) total_size=%u payload_size=%u "
                      "abi=%u phase=%u syscall_nr=%u capture_count=%u",
                      ret, ev->magic, YGG_SYSCALL_EVENT_MAGIC,
                      ev->version, YGG_SYSCALL_EVENT_VERSION,
                      ev->header_size, sizeof(*ev), ev->total_size,
                      ev->payload_size, ev->abi, ev->phase, ev->syscall_nr,
                      ev->capture_count);
            listener_error = -EPROTO;
            break;
        }

        const struct ygg_syscall_event *event = &record.event;
        const size_t comm_len = strnlen(event->comm, sizeof(event->comm));
        const std::string comm_text(event->comm, comm_len);
        jstring comm = env->NewStringUTF(comm_text.c_str());
        if (!comm) {
            env->ExceptionClear();
            comm = env->NewStringUTF("<invalid comm>");
        }
        if (!comm) {
            env->ExceptionClear();
            continue;
        }

        jlongArray args_before = env->NewLongArray(YGG_SYSCALL_ARG_COUNT);
        jlongArray args_after = env->NewLongArray(YGG_SYSCALL_ARG_COUNT);
        if (!args_before || !args_after) {
            env->ExceptionClear();
            env->DeleteLocalRef(comm);
            if (args_before) env->DeleteLocalRef(args_before);
            if (args_after) env->DeleteLocalRef(args_after);
            continue;
        }
        env->SetLongArrayRegion(
            args_before, 0, YGG_SYSCALL_ARG_COUNT,
            reinterpret_cast<const jlong *>(event->args_before));
        env->SetLongArrayRegion(
            args_after, 0, YGG_SYSCALL_ARG_COUNT,
            reinterpret_cast<const jlong *>(event->args_after));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(comm);
            env->DeleteLocalRef(args_before);
            env->DeleteLocalRef(args_after);
            continue;
        }
        jbyteArray capture_payload = env->NewByteArray((jsize)event->payload_size);
        if (!capture_payload) {
            env->ExceptionClear();
            env->DeleteLocalRef(comm);
            env->DeleteLocalRef(args_before);
            env->DeleteLocalRef(args_after);
            continue;
        }
        if (event->payload_size > 0) {
            env->SetByteArrayRegion(
                capture_payload, 0, (jsize)event->payload_size,
                reinterpret_cast<const jbyte *>(record.payload));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                env->DeleteLocalRef(comm);
                env->DeleteLocalRef(args_before);
                env->DeleteLocalRef(args_after);
                env->DeleteLocalRef(capture_payload);
                continue;
            }
        }

        const uint32_t target_id =
            (event->rule_flags & YGG_SYSCALL_F_TARGET_UID) ?
                event->target_uid : event->target_pid;
        env->CallVoidMethod(
            g_syscall_callback_ref, g_on_syscall_event_mid,
            (jlong)event->seq, (jlong)event->dropped, (jlong)event->call_id,
            (jint)event->phase, (jint)event->tid, (jint)event->tgid,
            (jint)event->syscall_nr, (jint)event->event_flags,
            (jint)event->rule_flags, (jlong)target_id,
            comm, (jlong)event->user_cookie, (jlong)event->arg_mask,
            args_before, args_after,
            (jlong)event->return_before, (jlong)event->return_after,
            (jint)event->capture_count, capture_payload);
        if (env->ExceptionCheck()) env->ExceptionClear();

        env->DeleteLocalRef(comm);
        env->DeleteLocalRef(args_before);
        env->DeleteLocalRef(args_after);
        env->DeleteLocalRef(capture_payload);
    }

    if (listener_error && !g_syscall_stop.exchange(true)) {
        notify_syscall_listener_failure(env, listener_error);
    }
    unregister_syscall_listener();
    g_jvm->DetachCurrentThread();
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_syscallStartListen(
        JNIEnv *env, jobject, jobject callback) {
    if (!callback) return -EINVAL;

    pthread_mutex_lock(&g_syscall_listen_mutex);
    if (g_syscall_listen_thread && g_syscall_stop.load()) {
        pthread_join(g_syscall_listen_thread, nullptr);
        g_syscall_listen_thread = 0;
        clear_syscall_callback(env);
    }
    if (g_syscall_listen_thread) {
        pthread_mutex_unlock(&g_syscall_listen_mutex);
        return 0;
    }

    jobject callback_ref = env->NewGlobalRef(callback);
    if (!callback_ref) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        pthread_mutex_unlock(&g_syscall_listen_mutex);
        return -ENOMEM;
    }
    jclass cls = env->GetObjectClass(callback_ref);
    if (!cls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(callback_ref);
        pthread_mutex_unlock(&g_syscall_listen_mutex);
        return -EINVAL;
    }
    jmethodID on_event_mid = env->GetMethodID(
        cls,
        "onEvent",
        "(JJJIIIIIIJLjava/lang/String;JJ[J[JJJI[B)V");
    if (!on_event_mid) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
        env->DeleteGlobalRef(callback_ref);
        pthread_mutex_unlock(&g_syscall_listen_mutex);
        return -EINVAL;
    }
    jmethodID on_listener_failure_mid = env->GetMethodID(
        cls,
        "onListenerFailure",
        "(I)V");
    env->DeleteLocalRef(cls);
    if (!on_listener_failure_mid) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(callback_ref);
        pthread_mutex_unlock(&g_syscall_listen_mutex);
        return -EINVAL;
    }

    g_syscall_callback_ref = callback_ref;
    g_on_syscall_event_mid = on_event_mid;
    g_on_syscall_listener_failure_mid = on_listener_failure_mid;
    g_syscall_stop.store(false);
    g_syscall_start_pending = true;
    g_syscall_start_result = -EIO;
    const int create_error = pthread_create(
        &g_syscall_listen_thread, nullptr, syscall_listen_thread, nullptr);
    if (create_error != 0) {
        g_syscall_listen_thread = 0;
        g_syscall_start_pending = false;
        clear_syscall_callback(env);
        g_syscall_stop.store(true);
        pthread_cond_broadcast(&g_syscall_start_cond);
    } else {
        while (g_syscall_start_pending) {
            pthread_cond_wait(&g_syscall_start_cond, &g_syscall_listen_mutex);
        }
        if (g_syscall_start_result != 0) {
            pthread_join(g_syscall_listen_thread, nullptr);
            g_syscall_listen_thread = 0;
            clear_syscall_callback(env);
        }
    }
    pthread_mutex_unlock(&g_syscall_listen_mutex);
    if (create_error != 0) {
        YGG_SLOGE("syscallStartListen: pthread_create failed (errno=%d)",
                  create_error);
        return -create_error;
    }
    if (g_syscall_start_result != 0) {
        YGG_SLOGE("syscallStartListen returning failure to Kotlin: errno=%d",
                  -g_syscall_start_result);
    } else {
        YGG_SLOGI("syscallStartListen: listener started OK");
    }
    return (jint)g_syscall_start_result;
}

extern "C" JNIEXPORT void JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_syscallStopListen(
        JNIEnv *env, jobject) {
    pthread_mutex_lock(&g_syscall_listen_mutex);
    g_syscall_stop.store(true);
    if (g_syscall_listen_thread) {
        pthread_join(g_syscall_listen_thread, nullptr);
        g_syscall_listen_thread = 0;
    }
    clear_syscall_callback(env);
    pthread_mutex_unlock(&g_syscall_listen_mutex);
}

struct EventRecord {
    struct ygg_hwbp_event_header header;
    uint8_t stack[YGG_HWBP_EVENT_STACK_MAX];
};

static void *listen_thread(void *) {
    JNIEnv *env = nullptr;
    if (!g_jvm || g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        return nullptr;
    }

    if (!g_on_event_mid) {
        g_jvm->DetachCurrentThread();
        return nullptr;
    }

    EventRecord rec;
    struct ygg_hwbp_read_event_request req = {};
    req.buffer      = &rec;
    req.buffer_size = sizeof(rec);
    struct timespec delay = {0, 100000000L};

    while (!g_stop.load()) {
        memset(&rec, 0, sizeof(rec));
        long ret = prctl(PR_YGGDRASIL_HWBP_READ_EVENT, &req, 0, 0, 0);
        if (ret < 0) {
            if (errno == EAGAIN) {
                nanosleep(&delay, nullptr);
                continue;
            }
            if (errno == EINTR) continue;
            nanosleep(&delay, nullptr);
            continue;
        }
        const struct ygg_hwbp_event_header *h = &rec.header;
        if ((uint32_t)ret < sizeof(*h) ||
            h->magic != YGG_HWBP_EVENT_MAGIC ||
            h->total_size != (uint32_t)ret) {
            continue;
        }

        const size_t comm_len = strnlen(h->comm, sizeof(h->comm));
        const std::string comm_text(h->comm, comm_len);
        jstring comm = env->NewStringUTF(comm_text.c_str());
        if (!comm) {
            env->ExceptionClear();
            comm = env->NewStringUTF("<invalid comm>");
        }
        if (!comm) {
            env->ExceptionClear();
            continue;
        }

        jlongArray regs = env->NewLongArray(31);
        if (regs) {
            env->SetLongArrayRegion(regs, 0, 31, (const jlong *)h->regs.regs);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        } else {
            env->ExceptionClear();
            regs = env->NewLongArray(0);
        }
        if (!regs) {
            env->DeleteLocalRef(comm);
            env->ExceptionClear();
            continue;
        }

        uint32_t stack_len = h->stack_copied_size;
        if (stack_len > YGG_HWBP_EVENT_STACK_MAX) stack_len = YGG_HWBP_EVENT_STACK_MAX;
        if (!(h->flags & YGG_HWBP_EVENT_FLAG_STACK)) stack_len = 0;
        jbyteArray stack = env->NewByteArray((jsize)stack_len);
        if (!stack) {
            env->ExceptionClear();
            stack_len = 0;
            stack = env->NewByteArray(0);
        }
        if (!stack) {
            env->DeleteLocalRef(comm);
            env->DeleteLocalRef(regs);
            env->ExceptionClear();
            continue;
        }
        if (stack_len > 0) {
            env->SetByteArrayRegion(stack, 0, (jsize)stack_len, (const jbyte *)rec.stack);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                env->DeleteLocalRef(stack);
                stack_len = 0;
                stack = env->NewByteArray(0);
                if (!stack) {
                    env->DeleteLocalRef(comm);
                    env->DeleteLocalRef(regs);
                    env->ExceptionClear();
                    continue;
                }
            }
        }

        env->CallVoidMethod(g_callback_ref, g_on_event_mid,
            (jlong)h->seq, (jlong)h->dropped,
            (jint)h->tid,  (jint)h->tgid,
            comm,
            (jint)h->mode, (jint)h->len, (jint)h->flags,
            (jlong)h->watched_addr,
            (jlong)h->regs.pc, (jlong)h->regs.sp, (jlong)h->regs.pstate,
            (jlong)h->stack_addr,
            (jint)h->stack_requested_size, (jint)stack_len,
            regs, stack);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }

        env->DeleteLocalRef(comm);
        env->DeleteLocalRef(regs);
        env->DeleteLocalRef(stack);
    }

    g_jvm->DetachCurrentThread();
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_hwbpStartListen(
        JNIEnv *env, jobject, jobject callback) {
    pthread_mutex_lock(&g_listen_mutex);
    if (g_listen_thread) {
        pthread_mutex_unlock(&g_listen_mutex);
        return;
    }

    g_callback_ref = env->NewGlobalRef(callback);
    if (!g_callback_ref) {
        pthread_mutex_unlock(&g_listen_mutex);
        return;
    }

    jclass cls = env->GetObjectClass(callback);
    g_on_event_mid = env->GetMethodID(cls, "onEvent",
        "(JJIILjava/lang/String;IIIJJJJJII[J[B)V");
    if (!g_on_event_mid) {
        env->DeleteGlobalRef(g_callback_ref);
        g_callback_ref = nullptr;
        pthread_mutex_unlock(&g_listen_mutex);
        return;
    }

    g_stop.store(false);
    const int create_error = pthread_create(&g_listen_thread, nullptr, listen_thread, nullptr);
    if (create_error != 0) {
        env->DeleteGlobalRef(g_callback_ref);
        g_callback_ref = nullptr;
        g_on_event_mid = nullptr;
    }
    pthread_mutex_unlock(&g_listen_mutex);
}

extern "C" JNIEXPORT void JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_hwbpStopListen(JNIEnv *env, jobject) {
    pthread_mutex_lock(&g_listen_mutex);
    g_stop.store(true);
    if (g_listen_thread) {
        pthread_join(g_listen_thread, nullptr);
        g_listen_thread = 0;
    }
    if (g_callback_ref) {
        env->DeleteGlobalRef(g_callback_ref);
        g_callback_ref = nullptr;
    }
    g_on_event_mid = nullptr;
    pthread_mutex_unlock(&g_listen_mutex);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_nativeHwbpUnwindDwarf(
        JNIEnv *env, jobject,
        jint pid, jint flags,
        jlongArray regsArr, jlong pc, jlong sp, jlong pstate,
        jlong stackAddr, jbyteArray stackArr, jstring mapsText) {
    (void)flags;
    if (!regsArr) return nullptr;

    jsize reg_count = env->GetArrayLength(regsArr);
    if (reg_count < 31) return nullptr;

    ygg_hwbp_user_regs_snapshot snap = {};
    jlong *reg_elems = env->GetLongArrayElements(regsArr, nullptr);
    if (!reg_elems) return nullptr;
    for (int i = 0; i < 31; ++i) snap.regs[i] = (uint64_t)reg_elems[i];
    env->ReleaseLongArrayElements(regsArr, reg_elems, JNI_ABORT);
    snap.pc = (uint64_t)pc;
    snap.sp = (uint64_t)sp;
    snap.pstate = (uint64_t)pstate;

    const uint8_t *stack_ptr = nullptr;
    jsize stack_len = 0;
    jbyte *stack_elems = nullptr;
    if (stackArr) {
        stack_len = env->GetArrayLength(stackArr);
        if (stack_len > 0) {
            stack_elems = env->GetByteArrayElements(stackArr, nullptr);
            stack_ptr = reinterpret_cast<const uint8_t *>(stack_elems);
        }
    }

    std::vector<YggBacktraceFrame> frames;
    std::string error;
    const char *maps_cstr = nullptr;
    if (mapsText) {
        maps_cstr = env->GetStringUTFChars(mapsText, nullptr);
    }

    ygg_unwind_dwarf(
        (uint32_t)pid, snap, (uint64_t)stackAddr,
        stack_ptr, (uint32_t)stack_len, maps_cstr, &frames, &error);

    if (maps_cstr) {
        env->ReleaseStringUTFChars(mapsText, maps_cstr);
    }
    if (stack_elems) {
        env->ReleaseByteArrayElements(stackArr, stack_elems, JNI_ABORT);
    }

    jclass string_cls = env->FindClass("java/lang/String");
    if (!string_cls) return nullptr;

    // packed[0] = note/error, packed[1..] = "pc\u0001label\u0001module\u0001offset"
    jsize out_len = (jsize)(1 + frames.size());
    jobjectArray out = env->NewObjectArray(out_len, string_cls, nullptr);
    if (!out) return nullptr;

    std::string note = error;
    jstring note_j = env->NewStringUTF(note.c_str());
    if (!note_j) return nullptr;
    env->SetObjectArrayElement(out, 0, note_j);
    env->DeleteLocalRef(note_j);

    for (size_t i = 0; i < frames.size(); ++i) {
        const YggBacktraceFrame &f = frames[i];
        char pc_hex[32];
        snprintf(pc_hex, sizeof(pc_hex), "%" PRIx64, (uint64_t)f.pc);
        std::string packed = std::string(pc_hex);
        packed.push_back('\1');
        packed += f.label;
        packed.push_back('\1');
        packed += f.module;
        packed.push_back('\1');
        packed += f.offset_hex;
        jstring row = env->NewStringUTF(packed.c_str());
        if (!row) return nullptr;
        env->SetObjectArrayElement(out, (jsize)(i + 1), row);
        env->DeleteLocalRef(row);
    }
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_nativeHwbpResolveLibraryTarget(
        JNIEnv *env, jobject,
        jint pid, jstring mapsText, jstring library, jlong offset) {
    if (!mapsText || !library) {
        jclass exception_cls = env->FindClass("java/lang/IllegalArgumentException");
        if (exception_cls) env->ThrowNew(exception_cls, "mapsText and library are required");
        return 0;
    }

    const char *maps_cstr = env->GetStringUTFChars(mapsText, nullptr);
    if (!maps_cstr) return 0;
    const char *library_cstr = env->GetStringUTFChars(library, nullptr);
    if (!library_cstr) {
        env->ReleaseStringUTFChars(mapsText, maps_cstr);
        return 0;
    }

    uint64_t address = 0;
    std::string error;
    const bool ok = ygg_resolve_library_target(
        static_cast<uint32_t>(pid), maps_cstr, library_cstr,
        static_cast<uint64_t>(offset), &address, &error);

    env->ReleaseStringUTFChars(library, library_cstr);
    env->ReleaseStringUTFChars(mapsText, maps_cstr);
    if (!ok) {
        jclass exception_cls = env->FindClass("java/lang/IllegalArgumentException");
        if (exception_cls) env->ThrowNew(exception_cls, error.c_str());
        return 0;
    }
    return static_cast<jlong>(address);
}
