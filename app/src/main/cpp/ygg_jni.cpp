#include <jni.h>
#include <sys/prctl.h>
#include <errno.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <atomic>
#include "yggdrasil.h"

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

        jstring comm  = env->NewStringUTF(h->comm);
        jlongArray regs = env->NewLongArray(31);
        env->SetLongArrayRegion(regs, 0, 31, (const jlong *)h->regs.regs);

        env->CallVoidMethod(g_callback_ref, g_on_event_mid,
            (jlong)h->seq, (jlong)h->dropped,
            (jint)h->tid,  (jint)h->tgid,
            comm,
            (jint)h->mode, (jint)h->len, (jint)h->flags,
            (jlong)h->watched_addr,
            (jlong)h->regs.pc, (jlong)h->regs.sp,
            regs);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }

        env->DeleteLocalRef(comm);
        env->DeleteLocalRef(regs);
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
    // Signature: (JJIILjava/lang/String;IIIJJJ[J)V
    g_on_event_mid = env->GetMethodID(cls, "onEvent",
        "(JJIILjava/lang/String;IIIJJJ[J)V");

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
