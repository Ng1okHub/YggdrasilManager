#include <jni.h>
#include <sys/prctl.h>
#include <errno.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <atomic>
#include <cinttypes>
#include <cstdio>
#include <string>
#include <vector>

#include "yggdrasil.h"
#include "ygg_backtrace.h"

static JavaVM *g_jvm = nullptr;
static jobject g_callback_ref = nullptr;
static jmethodID g_on_event_mid = nullptr;
static pthread_t g_listen_thread = 0;
static std::atomic<bool> g_stop{false};

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_checkAvailable(JNIEnv *, jobject) {
    return prctl(PR_YGGDRASIL_AVAILABLE, 0, 0, 0, 0) >= 0 ? JNI_TRUE : JNI_FALSE;
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

struct EventRecord {
    struct ygg_hwbp_event_header header;
    uint8_t stack[YGG_HWBP_EVENT_STACK_MAX];
};

static void *listen_thread(void *) {
    JNIEnv *env = nullptr;
    g_jvm->AttachCurrentThread(&env, nullptr);

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
            if (errno == EAGAIN) { nanosleep(&delay, nullptr); continue; }
            break;
        }
        const struct ygg_hwbp_event_header *h = &rec.header;
        if ((uint32_t)ret < sizeof(*h) || h->magic != YGG_HWBP_EVENT_MAGIC) continue;

        jstring comm = env->NewStringUTF(h->comm);
        if (!comm) { env->ExceptionClear(); continue; }
        jlongArray regs = env->NewLongArray(31);
        if (!regs) { env->DeleteLocalRef(comm); env->ExceptionClear(); continue; }
        env->SetLongArrayRegion(regs, 0, 31, (const jlong *)h->regs.regs);

        uint32_t stack_len = h->stack_copied_size;
        if (stack_len > YGG_HWBP_EVENT_STACK_MAX) stack_len = YGG_HWBP_EVENT_STACK_MAX;
        if (!(h->flags & YGG_HWBP_EVENT_FLAG_STACK)) stack_len = 0;
        jbyteArray stack = env->NewByteArray((jsize)stack_len);
        if (!stack) {
            env->DeleteLocalRef(comm);
            env->DeleteLocalRef(regs);
            env->ExceptionClear();
            continue;
        }
        if (stack_len > 0) {
            env->SetByteArrayRegion(stack, 0, (jsize)stack_len, (const jbyte *)rec.stack);
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
    if (g_callback_ref) env->DeleteGlobalRef(g_callback_ref);
    g_callback_ref = env->NewGlobalRef(callback);

    jclass cls = env->GetObjectClass(callback);
    g_on_event_mid = env->GetMethodID(cls, "onEvent",
        "(JJIILjava/lang/String;IIIJJJJJII[J[B)V");

    g_stop.store(false);
    if (pthread_create(&g_listen_thread, nullptr, listen_thread, nullptr) != 0) {
        env->DeleteGlobalRef(g_callback_ref);
        g_callback_ref = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_hwbpStopListen(JNIEnv *env, jobject) {
    g_stop.store(true);
    if (g_listen_thread) {
        pthread_join(g_listen_thread, nullptr);
        g_listen_thread = 0;
    }
    if (g_callback_ref) {
        env->DeleteGlobalRef(g_callback_ref);
        g_callback_ref = nullptr;
    }
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
