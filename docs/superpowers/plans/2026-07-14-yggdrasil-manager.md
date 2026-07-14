# YggdrasilManager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build YggdrasilManager — a Kotlin/Compose Android app that exposes Yggdrasil KPM kernel module functionality (HWBP Observe/Unobserve/Events + Hide DBG) via a JNI prctl() bridge with libsu root elevation.

**Architecture:** MVVM with StateFlow; Compose UI with 2-tab bottom nav (主頁 | 功能); JNI C++ calls prctl() directly after libsu elevates the app process at startup; SharedFlow streams HWBP events from a background JNI polling thread.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose BOM 2024.12.01, Navigation Compose 2.7.7, Lifecycle-ViewModel-Compose 2.8.7, libsu 5.2.0, AGP 9.2.1, NDK/CMake 3.22.1, JUnit 4, kotlinx-coroutines-test 1.8.1, MockK 1.13.13.

## Global Constraints

- `minSdk 24`, `targetSdk 36`, compileSdk AGP 9.x (`release(36) { minorApiLevel = 1 }`)
- Package: `ng1ok.yggdrasilmanager`
- JNI library name: `yggdrasilmanager` → `libyggdrasilmanager.so`
- `yggdrasil.h` is read-only reference at `C:\Users\Administrator\Desktop\KernelPatchModules-main\kpms\Yggdrasil\yggdrasil.h` — copy verbatim to `app/src/main/cpp/yggdrasil.h`
- All prctl() constants come from `yggdrasil.h`; never hardcode hex values in C++ or Kotlin
- libsu distributed via JitPack — add `maven { url = uri("https://jitpack.io") }` to `settings.gradle` dependencyResolutionManagement
- Out-of-scope: HWBP Attach/Replace/Detach, memfd, VMA merge, symbol resolution UI
- Events list capped at 200 entries (drop oldest)
- Stack size input: default 4096, max 65536

---

## File Map

**Build files (modify):**
- `gradle/libs.versions.toml` — add kotlin, compose BOM, navigation, lifecycle, libsu, test versions+libs+plugins
- `app/build.gradle` — add kotlin-android + compose-compiler plugins; enable compose build feature
- `settings.gradle` — add JitPack repository

**Native (create):**
- `app/src/main/cpp/yggdrasil.h` — verbatim copy of KPM ABI header
- `app/src/main/cpp/ygg_jni.cpp` — all JNI function implementations
- `app/src/main/cpp/CMakeLists.txt` — change source from `native-lib.cpp` to `ygg_jni.cpp`

**Delete:**
- `app/src/main/java/ng1ok/yggdrasilmanager/MainActivity.java`
- `app/src/main/cpp/native-lib.cpp`

**Kotlin source (create):**
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/MainActivity.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/native/YggdrasilNative.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/model/HwbpEvent.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/model/HwbpObserveParams.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/YggdrasilRepository.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Color.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Type.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Theme.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/BottomNavItem.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/NavGraph.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeViewModel.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeScreen.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/features/FeaturesScreen.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpViewModel.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpScreen.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt`
- `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgScreen.kt`

**Manifest (modify):**
- `app/src/main/AndroidManifest.xml` — add `android:name=".App"`

**Tests (create):**
- `app/src/test/kotlin/ng1ok/yggdrasilmanager/RepositoryTest.kt`
- `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpViewModelTest.kt`
- `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgViewModelTest.kt`

---
### Task 1: Build Configuration — Kotlin + Compose + libsu

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle`
- Modify: `settings.gradle`

**Interfaces:**
- Consumes: nothing
- Produces: compilable Kotlin + Compose project with libsu on classpath

**Reference — current `gradle/libs.versions.toml` (full):**
```toml
[versions]
agp = "9.2.1"
junit = "4.13.2"
junitVersion = "1.2.1"
espressoCore = "3.6.1"
appcompat = "1.7.1"
material = "1.14.0"
constraintlayout = "2.2.1"

[libraries]
junit = { group = "junit", name = "junit", version.ref = "junit" }
ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

- [ ] **Step 1: Replace `gradle/libs.versions.toml` with the full updated version**

```toml
[versions]
agp = "9.2.1"
kotlin = "2.0.21"
compose-bom = "2024.12.01"
navigation-compose = "2.7.7"
lifecycle = "2.8.7"
libsu = "5.2.0"
junit = "4.13.2"
junitVersion = "1.2.1"
espressoCore = "3.6.1"
coroutines-test = "1.8.1"
mockk = "1.13.13"

[libraries]
# Compose
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.3" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation-compose" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
# Root
libsu-core = { group = "com.github.topjohnwu.libsu", name = "core", version.ref = "libsu" }
# Test
junit = { group = "junit", name = "junit", version.ref = "junit" }
ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines-test" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Add JitPack repo to `settings.gradle`**

Find the `dependencyResolutionManagement { repositories { ... } }` block and add:
```groovy
maven { url = uri("https://jitpack.io") }
```

Full `dependencyResolutionManagement` block after edit:
```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

- [ ] **Step 3: Replace `app/build.gradle` plugins block and add compose deps**

Current plugins block:
```groovy
plugins {
    alias(libs.plugins.android.application)
}
```

Replace with:
```groovy
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}
```

Inside `android { ... }`, add after the existing `defaultConfig` block:
```groovy
buildFeatures {
    compose = true
}
```

Replace the entire `dependencies { }` block with:
```groovy
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.libsu.core)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
```

- [ ] **Step 4: Verify the project compiles**

Run in the project root:
```
gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If you see "Unresolved reference" for Compose, confirm `buildFeatures { compose = true }` is inside the `android { }` block.

- [ ] **Step 5: Commit**

```
git add gradle/libs.versions.toml app/build.gradle settings.gradle
git commit -m "build: migrate to Kotlin + Compose + libsu"
```

---
### Task 2: Data Models + JNI Kotlin Interface

**Files:**
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/model/HwbpEvent.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/model/HwbpObserveParams.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/native/YggdrasilNative.kt`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/ModelTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `HwbpEvent(seq, dropped, tid, tgid, comm, mode, len, flags, watchedAddr, pc, sp, regs)` data class
  - `HwbpObserveParams(pid, targetAddr, mode, len, flags, stackSize)` data class
  - `HwbpEventCallback` interface with `onEvent(...)`
  - `YggdrasilNativeApi` interface
  - `YggdrasilNative : YggdrasilNativeApi` object with `external fun` declarations + `System.loadLibrary`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/ModelTest.kt`:
```kotlin
package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTest {
    @Test
    fun hwbpObserveParams_equality() {
        val a = HwbpObserveParams(pid = 1234, targetAddr = 0xDEADBEEFL, mode = 1, len = 4, flags = 0, stackSize = 4096)
        val b = HwbpObserveParams(pid = 1234, targetAddr = 0xDEADBEEFL, mode = 1, len = 4, flags = 0, stackSize = 4096)
        assertEquals(a, b)
    }

    @Test
    fun hwbpEvent_modeLabel() {
        val event = HwbpEvent(seq = 1L, dropped = 0L, tid = 100, tgid = 100,
            comm = "test", mode = 1, len = 4, flags = 0,
            watchedAddr = 0xABCDL, pc = 0x1000L, sp = 0x2000L, regs = LongArray(31))
        assertEquals(1, event.mode)
        assertEquals("test", event.comm)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.ModelTest"
```
Expected: FAIL — `HwbpObserveParams` and `HwbpEvent` not found.

- [ ] **Step 3: Create `HwbpObserveParams.kt`**

```kotlin
package ng1ok.yggdrasilmanager.data.model

data class HwbpObserveParams(
    val pid: Int,
    val targetAddr: Long,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val stackSize: Int
)
```

- [ ] **Step 4: Create `HwbpEvent.kt`**

```kotlin
package ng1ok.yggdrasilmanager.data.model

data class HwbpEvent(
    val seq: Long,
    val dropped: Long,
    val tid: Int,
    val tgid: Int,
    val comm: String,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val watchedAddr: Long,
    val pc: Long,
    val sp: Long,
    val regs: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HwbpEvent) return false
        return seq == other.seq && tid == other.tid && tgid == other.tgid
    }
    override fun hashCode(): Int = seq.hashCode()
}
```

- [ ] **Step 5: Create `YggdrasilNative.kt`**

```kotlin
package ng1ok.yggdrasilmanager.native

interface YggdrasilNativeApi {
    fun checkAvailable(): Boolean
    fun hwbpObserve(pid: Int, targetAddr: Long, mode: Int, len: Int, flags: Int, stackSize: Int): Int
    fun hwbpUnobserve(pid: Int, targetAddr: Long): Int
    fun hwbpStartListen(callback: HwbpEventCallback)
    fun hwbpStopListen()
    fun hideDbg(enable: Boolean, hasUid: Boolean, uid: Int): Int
}

interface HwbpEventCallback {
    fun onEvent(
        seq: Long, dropped: Long, tid: Int, tgid: Int, comm: String,
        mode: Int, len: Int, flags: Int, watchedAddr: Long,
        pc: Long, sp: Long, regs: LongArray
    )
}

object YggdrasilNative : YggdrasilNativeApi {
    init {
        System.loadLibrary("yggdrasilmanager")
    }

    external override fun checkAvailable(): Boolean
    external override fun hwbpObserve(pid: Int, targetAddr: Long, mode: Int, len: Int, flags: Int, stackSize: Int): Int
    external override fun hwbpUnobserve(pid: Int, targetAddr: Long): Int
    external override fun hwbpStartListen(callback: HwbpEventCallback)
    external override fun hwbpStopListen()
    external override fun hideDbg(enable: Boolean, hasUid: Boolean, uid: Int): Int
}
```

- [ ] **Step 6: Run tests to verify they pass**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.ModelTest"
```
Expected: PASS.

- [ ] **Step 7: Commit**

```
git add app/src/main/kotlin app/src/test/kotlin
git commit -m "feat: add data models and JNI Kotlin interface"
```

---
### Task 3: Native JNI Layer (C++)

**Files:**
- Create: `app/src/main/cpp/yggdrasil.h` (copy from KPM source)
- Create: `app/src/main/cpp/ygg_jni.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Delete: `app/src/main/cpp/native-lib.cpp`

**Interfaces:**
- Consumes: `YggdrasilNativeApi` signatures from Task 2 (determines JNI function names)
- Produces: `libyggdrasilmanager.so` implementing all 6 JNI functions

JNI function naming rule: `Java_<package_underscored>_<class>_<method>`
Package `ng1ok.yggdrasilmanager.native` → `ng1ok_yggdrasilmanager_native`
Example: `Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_checkAvailable`

- [ ] **Step 1: Copy `yggdrasil.h` from KPM source**

Copy `C:\Users\Administrator\Desktop\KernelPatchModules-main\kpms\Yggdrasil\yggdrasil.h`
to `app/src/main/cpp/yggdrasil.h` verbatim. No edits.

- [ ] **Step 2: Update `CMakeLists.txt`**

Change line 28 from `native-lib.cpp` to `ygg_jni.cpp`:
```cmake
add_library(${CMAKE_PROJECT_NAME} SHARED
        ygg_jni.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
```

- [ ] **Step 3: Delete `native-lib.cpp`**

```
rm app/src/main/cpp/native-lib.cpp
```

- [ ] **Step 4: Create `app/src/main/cpp/ygg_jni.cpp`**

```cpp
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
    pthread_create(&g_listen_thread, nullptr, listen_thread, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_ng1ok_yggdrasilmanager_native_YggdrasilNative_hwbpStopListen(JNIEnv *, jobject) {
    g_stop.store(true);
    if (g_listen_thread) {
        pthread_join(g_listen_thread, nullptr);
        g_listen_thread = 0;
    }
    if (g_callback_ref && g_jvm) {
        JNIEnv *env = nullptr;
        g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (env) env->DeleteGlobalRef(g_callback_ref);
        g_callback_ref = nullptr;
    }
}
```

**JNI method signature for `onEvent`:**
Parameters: `(seq:J, dropped:J, tid:I, tgid:I, comm:Ljava/lang/String;, mode:I, len:I, flags:I, watchedAddr:J, pc:J, sp:J, regs:[J)V`
Full descriptor: `(JJIILjava/lang/String;IIIJJJ[J)V`

- [ ] **Step 5: Verify native compile**

```
gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL` with no CMake errors. If you see `undefined reference to prctl`, verify NDK is set up in `local.properties` (`ndk.dir=...`).

- [ ] **Step 6: Commit**

```
git add app/src/main/cpp/
git commit -m "feat: implement JNI native layer (ygg_jni.cpp)"
```

---
### Task 4: App.kt + YggdrasilRepository

**Files:**
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/YggdrasilRepository.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/RepositoryTest.kt`

**Interfaces:**
- Consumes: `YggdrasilNativeApi`, `HwbpEventCallback`, `HwbpEvent`, `HwbpObserveParams` from Tasks 2–3
- Produces:
  - `YggdrasilRepository.instance` singleton
  - `suspend fun checkAvailable(): Boolean`
  - `suspend fun hwbpObserve(params: HwbpObserveParams): Result<Unit>`
  - `suspend fun hwbpUnobserve(pid: Int, targetAddr: Long): Result<Unit>`
  - `val hwbpEvents: SharedFlow<HwbpEvent>` (lazy — starts JNI listen on first subscriber)
  - `suspend fun setHideDbg(enable: Boolean, uid: Int?): Result<Unit>`
  - `fun startEventStream()` / `fun stopEventStream()`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/RepositoryTest.kt`:
```kotlin
package ng1ok.yggdrasilmanager

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class RepositoryTest {
    private lateinit var mockNative: YggdrasilNativeApi
    private lateinit var repo: YggdrasilRepository

    @Before
    fun setup() {
        mockNative = mockk(relaxed = true)
        repo = YggdrasilRepository(mockNative)
    }

    @Test
    fun checkAvailable_returnsTrue_whenNativeReturnsTrue() = runTest {
        every { mockNative.checkAvailable() } returns true
        assertTrue(repo.checkAvailable())
    }

    @Test
    fun checkAvailable_returnsFalse_whenNativeReturnsFalse() = runTest {
        every { mockNative.checkAvailable() } returns false
        assertFalse(repo.checkAvailable())
    }

    @Test
    fun hwbpObserve_returnsSuccess_whenNativeReturns0() = runTest {
        every { mockNative.hwbpObserve(any(), any(), any(), any(), any(), any()) } returns 0
        val params = HwbpObserveParams(pid = 1234, targetAddr = 0x1000L, mode = 1, len = 4, flags = 0, stackSize = 4096)
        val result = repo.hwbpObserve(params)
        assertTrue(result.isSuccess)
    }

    @Test
    fun hwbpObserve_returnsFailure_whenNativeReturnsNegative() = runTest {
        every { mockNative.hwbpObserve(any(), any(), any(), any(), any(), any()) } returns -13
        val params = HwbpObserveParams(pid = 1234, targetAddr = 0x1000L, mode = 1, len = 4, flags = 0, stackSize = 4096)
        val result = repo.hwbpObserve(params)
        assertTrue(result.isFailure)
    }

    @Test
    fun setHideDbg_global_callsNativeWithoutUid() = runTest {
        every { mockNative.hideDbg(any(), any(), any()) } returns 0
        repo.setHideDbg(enable = true, uid = null)
        verify { mockNative.hideDbg(enable = true, hasUid = false, uid = 0) }
    }

    @Test
    fun setHideDbg_perUid_callsNativeWithUid() = runTest {
        every { mockNative.hideDbg(any(), any(), any()) } returns 0
        repo.setHideDbg(enable = true, uid = 10086)
        verify { mockNative.hideDbg(enable = true, hasUid = true, uid = 10086) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.RepositoryTest"
```
Expected: FAIL — `YggdrasilRepository` not found.

- [ ] **Step 3: Create `App.kt`**

```kotlin
package ng1ok.yggdrasilmanager

import android.app.Application
import com.topjohnwu.superuser.Shell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }
}
```

- [ ] **Step 4: Add `android:name=".App"` to `AndroidManifest.xml`**

Find `<application` tag and add the attribute:
```xml
<application
    android:name=".App"
    android:allowBackup="true"
    ...
```

- [ ] **Step 5: Create `YggdrasilRepository.kt`**

```kotlin
package ng1ok.yggdrasilmanager.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNative
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi

class YggdrasilRepository(
    private val native: YggdrasilNativeApi = YggdrasilNative
) {
    private val _hwbpEvents = MutableSharedFlow<HwbpEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val hwbpEvents: SharedFlow<HwbpEvent> = _hwbpEvents.asSharedFlow()

    private val callback = object : HwbpEventCallback {
        override fun onEvent(
            seq: Long, dropped: Long, tid: Int, tgid: Int, comm: String,
            mode: Int, len: Int, flags: Int, watchedAddr: Long,
            pc: Long, sp: Long, regs: LongArray
        ) {
            _hwbpEvents.tryEmit(
                HwbpEvent(seq, dropped, tid, tgid, comm, mode, len, flags, watchedAddr, pc, sp, regs)
            )
        }
    }

    suspend fun checkAvailable(): Boolean = withContext(Dispatchers.IO) {
        native.checkAvailable()
    }

    suspend fun hwbpObserve(params: HwbpObserveParams): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = native.hwbpObserve(params.pid, params.targetAddr, params.mode, params.len, params.flags, params.stackSize)
        if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
    }

    suspend fun hwbpUnobserve(pid: Int, targetAddr: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = native.hwbpUnobserve(pid, targetAddr)
        if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
    }

    suspend fun setHideDbg(enable: Boolean, uid: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = native.hideDbg(enable, uid != null, uid ?: 0)
        if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
    }

    fun startEventStream() {
        native.hwbpStartListen(callback)
    }

    fun stopEventStream() {
        native.hwbpStopListen()
    }

    companion object {
        val instance: YggdrasilRepository by lazy { YggdrasilRepository() }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.RepositoryTest"
```
Expected: all 6 tests PASS.

- [ ] **Step 7: Commit**

```
git add app/src/main/kotlin app/src/main/AndroidManifest.xml app/src/test/kotlin
git commit -m "feat: add App.kt, YggdrasilRepository, update manifest"
```

---
### Task 5: Theme + Navigation Scaffold + MainActivity

**Files:**
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Color.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Type.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/BottomNavItem.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/NavGraph.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/MainActivity.kt`
- Delete: `app/src/main/java/ng1ok/yggdrasilmanager/MainActivity.java`

**Interfaces:**
- Consumes: Compose BOM + Navigation Compose from Task 1
- Produces:
  - `YggdrasilTheme { }` composable wrapper
  - `sealed class BottomNavItem` with `Home` and `Features` objects
  - `NavGraph(navController, modifier)` composable with all routes registered
  - `MainActivity` with `setContent { YggdrasilTheme { MainScaffold() } }`

- [ ] **Step 1: Create `Color.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)
```

- [ ] **Step 2: Create `Type.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)
```

- [ ] **Step 3: Create `Theme.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun YggdrasilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
```

- [ ] **Step 4: Create `BottomNavItem.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    object Home : BottomNavItem("home", "主頁", Icons.Default.Home)
    object Features : BottomNavItem("features", "功能", Icons.Default.List)
}
```

- [ ] **Step 5: Create `NavGraph.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ng1ok.yggdrasilmanager.ui.features.FeaturesScreen
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgScreen
import ng1ok.yggdrasilmanager.ui.home.HomeScreen
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpScreen

@Composable
fun NavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = BottomNavItem.Home.route, modifier = modifier) {
        composable(BottomNavItem.Home.route) { HomeScreen() }
        composable(BottomNavItem.Features.route) { FeaturesScreen(navController) }
        composable("hwbp") { HwbpScreen() }
        composable("hidedbg") { HideDbgScreen() }
    }
}
```

- [ ] **Step 6: Create `MainActivity.kt` and delete `MainActivity.java`**

Delete `app/src/main/java/ng1ok/yggdrasilmanager/MainActivity.java`.

Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/MainActivity.kt`:
```kotlin
package ng1ok.yggdrasilmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ng1ok.yggdrasilmanager.ui.navigation.BottomNavItem
import ng1ok.yggdrasilmanager.ui.navigation.NavGraph
import ng1ok.yggdrasilmanager.ui.theme.YggdrasilTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YggdrasilTheme {
                MainScaffold()
            }
        }
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val tabs = listOf(BottomNavItem.Home, BottomNavItem.Features)
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavGraph(navController = navController, modifier = Modifier.padding(innerPadding))
    }
}
```

- [ ] **Step 7: Verify compile**

```
gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. The app will crash at runtime until screens are created — that is expected.

- [ ] **Step 8: Commit**

```
git add app/src/main/kotlin app/src/main/java
git commit -m "feat: theme, navigation scaffold, and MainActivity"
```

---
### Task 6: HomeScreen + HomeViewModel

**Files:**
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeViewModel.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeScreen.kt`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `YggdrasilRepository.instance` from Task 4
- Produces: `HomeScreen()` composable (no parameters)

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HomeViewModelTest.kt`:
```kotlin
package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.ui.home.HomeUiState
import ng1ok.yggdrasilmanager.ui.home.HomeViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockRepo: YggdrasilRepository
    private lateinit var vm: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        vm = HomeViewModel(mockRepo)
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun refresh_setsRootGranted_whenShellGranted() = runTest {
        coEvery { mockRepo.checkAvailable() } returns true
        vm.refresh(rootGranted = true)
        val state = vm.uiState.value
        assertEquals(true, state.rootGranted)
        assertEquals(true, state.moduleAvailable)
    }

    @Test
    fun refresh_setsModuleUnavailable_whenCheckFails() = runTest {
        coEvery { mockRepo.checkAvailable() } returns false
        vm.refresh(rootGranted = true)
        assertEquals(false, vm.uiState.value.moduleAvailable)
    }

    @Test
    fun initialState_isLoading() {
        assertEquals(HomeUiState(), vm.uiState.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.HomeViewModelTest"
```
Expected: FAIL — `HomeViewModel` not found.

- [ ] **Step 3: Create `HomeViewModel.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository

data class HomeUiState(
    val rootGranted: Boolean = false,
    val moduleAvailable: Boolean = false,
    val loading: Boolean = false
)

class HomeViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh(rootGranted: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val available = if (rootGranted) repo.checkAvailable() else false
            _uiState.value = HomeUiState(rootGranted = rootGranted, moduleAvailable = available)
        }
    }
}
```

- [ ] **Step 4: Create `HomeScreen.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.superuser.Shell

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        Shell.getShell { shell ->
            vm.refresh(rootGranted = shell.isRoot)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("YggdrasilManager", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        StatusCard(
            title = "Root 權限",
            ok = state.rootGranted,
            okText = "已獲取",
            failText = "未獲取"
        )
        StatusCard(
            title = "Yggdrasil 模塊",
            ok = state.moduleAvailable,
            okText = "可用",
            failText = if (!state.rootGranted) "需要Root" else "不可用"
        )
    }
}

@Composable
private fun StatusCard(title: String, ok: Boolean, okText: String, failText: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    if (ok) okText else failText,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.HomeViewModelTest"
```
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home app/src/test/kotlin
git commit -m "feat: HomeScreen and HomeViewModel"
```

---
### Task 7: FeaturesScreen

**Files:**
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/features/FeaturesScreen.kt`

**Interfaces:**
- Consumes: `NavGraph` routes `"hwbp"` and `"hidedbg"` from Task 5
- Produces: `FeaturesScreen(navController)` composable

- [ ] **Step 1: Create `FeaturesScreen.kt`**

No test needed for pure navigation glue. The screen is a card registry — adding a new feature means adding one `FeatureItem` entry.

```kotlin
package ng1ok.yggdrasilmanager.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private data class FeatureItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String
)

private val features = listOf(
    FeatureItem(
        title = "HWBP Observe",
        description = "通過硬件斷點觀察進程內存訪問，實時查看觸發事件",
        icon = Icons.Default.Visibility,
        route = "hwbp"
    ),
    FeatureItem(
        title = "Hide DBG",
        description = "隱藏調試器狀態，支持全局模式和按UID精確控制",
        icon = Icons.Default.BugReport,
        route = "hidedbg"
    )
)

@Composable
fun FeaturesScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("功能", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        features.forEach { feature ->
            FeatureCard(feature = feature, onClick = { navController.navigate(feature.route) })
        }
    }
}

@Composable
private fun FeatureCard(feature: FeatureItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(feature.title, style = MaterialTheme.typography.titleMedium)
                Text(feature.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

```
gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/features
git commit -m "feat: FeaturesScreen with extensible feature card registry"
```

---

### Task 8: HwbpScreen + HwbpViewModel

**Files:**
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpViewModel.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpScreen.kt`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpViewModelTest.kt`

**Interfaces:**
- Consumes: `YggdrasilRepository.instance`, `HwbpObserveParams`, `HwbpEvent`
- Produces: `HwbpScreen()` composable with Observe tab + Events tab

Mode constants (from `yggdrasil.h`):
- `YGG_HWBP_MODE_R=1`, `W=2`, `RW=3`, `X=4`
- `YGG_HWBP_F_SINGLE_THREAD=1`, `YGG_HWBP_F_FILTER_SYSTEM=2`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpViewModelTest.kt`:
```kotlin
package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HwbpViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockRepo: YggdrasilRepository
    private lateinit var vm: HwbpViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        vm = HwbpViewModel(mockRepo)
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun addObserve_addsToList_onSuccess() = runTest {
        coEvery { mockRepo.hwbpObserve(any()) } returns Result.success(Unit)
        val params = HwbpObserveParams(1234, 0x1000L, 1, 4, 0, 4096)
        vm.addObserve(params)
        assertEquals(1, vm.observePoints.value.size)
        assertEquals(params, vm.observePoints.value.first())
    }

    @Test
    fun addObserve_doesNotAdd_onFailure() = runTest {
        coEvery { mockRepo.hwbpObserve(any()) } returns Result.failure(RuntimeException("err"))
        val params = HwbpObserveParams(1234, 0x1000L, 1, 4, 0, 4096)
        vm.addObserve(params)
        assertEquals(0, vm.observePoints.value.size)
    }

    @Test
    fun removeObserve_removesFromList_onSuccess() = runTest {
        coEvery { mockRepo.hwbpObserve(any()) } returns Result.success(Unit)
        coEvery { mockRepo.hwbpUnobserve(any(), any()) } returns Result.success(Unit)
        val params = HwbpObserveParams(1234, 0x1000L, 1, 4, 0, 4096)
        vm.addObserve(params)
        vm.removeObserve(params)
        assertEquals(0, vm.observePoints.value.size)
    }

    @Test
    fun clearEvents_emptiesEventList() = runTest {
        vm.clearEvents()
        assertEquals(0, vm.events.value.size)
    }

    @Test
    fun events_cappedAt200() = runTest {
        repeat(250) { i ->
            vm.appendEvent(HwbpEvent(i.toLong(), 0, 0, 0, "t", 1, 4, 0, 0L, 0L, 0L, LongArray(31)))
        }
        assertEquals(200, vm.events.value.size)
        assertEquals(249L, vm.events.value.last().seq)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.HwbpViewModelTest"
```
Expected: FAIL — `HwbpViewModel` not found.

- [ ] **Step 3: Create `HwbpViewModel.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.hwbp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

private const val MAX_EVENTS = 200

class HwbpViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance
) : ViewModel() {

    private val _observePoints = MutableStateFlow<List<HwbpObserveParams>>(emptyList())
    val observePoints: StateFlow<List<HwbpObserveParams>> = _observePoints.asStateFlow()

    private val _events = MutableStateFlow<List<HwbpEvent>>(emptyList())
    val events: StateFlow<List<HwbpEvent>> = _events.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        repo.startEventStream()
        viewModelScope.launch {
            repo.hwbpEvents.collect { event -> appendEvent(event) }
        }
    }

    fun addObserve(params: HwbpObserveParams) {
        viewModelScope.launch {
            repo.hwbpObserve(params)
                .onSuccess { _observePoints.value = _observePoints.value + params }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun removeObserve(params: HwbpObserveParams) {
        viewModelScope.launch {
            repo.hwbpUnobserve(params.pid, params.targetAddr)
                .onSuccess { _observePoints.value = _observePoints.value - params }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun clearEvents() { _events.value = emptyList() }
    fun dismissError() { _error.value = null }

    internal fun appendEvent(event: HwbpEvent) {
        val current = _events.value
        _events.value = if (current.size >= MAX_EVENTS) current.drop(1) + event else current + event
    }

    override fun onCleared() {
        super.onCleared()
        repo.stopEventStream()
    }
}
```

- [ ] **Step 4: Create `HwbpScreen.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.hwbp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

private val MODES = listOf("R" to 1, "W" to 2, "RW" to 3, "X" to 4)
private val LENS = listOf(1, 2, 4, 8)

@Composable
fun HwbpScreen(vm: HwbpViewModel = viewModel()) {
    val observePoints by vm.observePoints.collectAsState()
    val events by vm.events.collectAsState()
    val error by vm.error.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            text = { Text(msg) }
        )
    }

    Scaffold(
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add observe point")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Observe") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Events") })
            }
            when (selectedTab) {
                0 -> ObserveTab(points = observePoints, onRemove = vm::removeObserve)
                1 -> EventsTab(events = events, onClear = vm::clearEvents)
            }
        }
    }

    if (showAddSheet) {
        AddObserveSheet(
            onDismiss = { showAddSheet = false },
            onConfirm = { params -> vm.addObserve(params); showAddSheet = false }
        )
    }
}

@Composable
private fun ObserveTab(points: List<HwbpObserveParams>, onRemove: (HwbpObserveParams) -> Unit) {
    if (points.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No observe points", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(points) { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "PID: ${p.pid}  Addr: 0x${p.targetAddr.toString(16).uppercase()}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Mode: ${MODES.firstOrNull { it.second == p.mode }?.first ?: p.mode}  Len: ${p.len}  Stack: ${p.stackSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onRemove(p) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsTab(events: List<HwbpEvent>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${events.size} events", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for events...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(events, key = { it.seq }) { e -> EventCard(e) }
        }
    }
}

@Composable
private fun EventCard(e: HwbpEvent) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("#${e.seq}", style = MaterialTheme.typography.labelSmall)
                if (e.dropped > 0) Text(
                    "dropped:${e.dropped}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text("${e.comm} (tid=${e.tid} tgid=${e.tgid})", style = MaterialTheme.typography.bodyMedium)
            Text(
                "watched=0x${e.watchedAddr.toString(16).uppercase()}  pc=0x${e.pc.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text("sp=0x${e.sp.toString(16).uppercase()}", style = MaterialTheme.typography.bodySmall)
                e.regs.forEachIndexed { i, v ->
                    Text("x$i=0x${v.toString(16).uppercase()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddObserveSheet(onDismiss: () -> Unit, onConfirm: (HwbpObserveParams) -> Unit) {
    var addrText by remember { mutableStateOf("") }
    var pidText by remember { mutableStateOf("") }
    var modeIdx by remember { mutableIntStateOf(0) }
    var lenIdx by remember { mutableIntStateOf(2) }
    var singleThread by remember { mutableStateOf(false) }
    var filterSystem by remember { mutableStateOf(false) }
    var stackText by remember { mutableStateOf("4096") }

    val selectedMode = MODES[modeIdx].second
    val effectiveLenIdx = if (selectedMode == 4) LENS.indexOf(4).coerceAtLeast(0) else lenIdx

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add Observe Point", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = addrText, onValueChange = { addrText = it },
                label = { Text("Target address (hex, e.g. 7f1a2b3c)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            OutlinedTextField(
                value = pidText, onValueChange = { pidText = it },
                label = { Text("Target PID") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODES.forEachIndexed { i, (label, _) ->
                    FilterChip(selected = modeIdx == i, onClick = { modeIdx = i }, label = { Text(label) })
                }
            }
            Text("Len (bytes)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LENS.forEachIndexed { i, len ->
                    FilterChip(
                        selected = effectiveLenIdx == i,
                        onClick = { if (selectedMode != 4) lenIdx = i },
                        label = { Text("$len") },
                        enabled = selectedMode != 4
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Single Thread")
                Switch(checked = singleThread, onCheckedChange = { singleThread = it })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Filter System")
                Switch(checked = filterSystem, onCheckedChange = { filterSystem = it })
            }
            OutlinedTextField(
                value = stackText, onValueChange = { stackText = it },
                label = { Text("Stack size (max 65536)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val addr = addrText.trimStart('0', 'x', 'X').toLongOrNull(16) ?: return@Button
                    val pid = pidText.toIntOrNull() ?: return@Button
                    val stack = stackText.toIntOrNull()?.coerceIn(0, 65536) ?: 4096
                    val flags = (if (singleThread) 1 else 0) or (if (filterSystem) 2 else 0)
                    onConfirm(HwbpObserveParams(pid, addr, selectedMode, LENS[effectiveLenIdx], flags, stack))
                }) { Text("Confirm") }
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.HwbpViewModelTest"
```
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp app/src/test/kotlin
git commit -m "feat: HwbpScreen and HwbpViewModel (Observe + Events tabs)"
```

---

### Task 9: HideDbgScreen + HideDbgViewModel

**Files:**
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgScreen.kt`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgViewModelTest.kt`

**Interfaces:**
- Consumes: `YggdrasilRepository.instance`, `YggdrasilRepository.setHideDbg(enable, uid)`
- Produces: `HideDbgScreen()` composable with global switch + per-UID rule list

`setHideDbg` semantics (from `yggdrasil.h` `ygg_hide_dbg_request`):
- Global enable/disable: `enable=true/false`, `uid=null` → `hasUid=false`, `flags=0`
- Per-UID rule enable: `enable=true`, `uid=<int>` → `hasUid=true`, `flags=YGG_HIDE_DBG_F_TARGET_UID(1)`
- Per-UID rule disable (remove): `enable=false`, `uid=<int>` → `hasUid=true`, `flags=YGG_HIDE_DBG_F_TARGET_UID(1)`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgViewModelTest.kt`:
```kotlin
package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HideDbgViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockRepo: YggdrasilRepository
    private lateinit var vm: HideDbgViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        vm = HideDbgViewModel(mockRepo)
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun toggleGlobal_enablesAndCallsRepo() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        assertFalse(vm.globalEnabled.value)
        vm.toggleGlobal()
        assertTrue(vm.globalEnabled.value)
        coVerify { mockRepo.setHideDbg(true, null) }
    }

    @Test
    fun toggleGlobal_disablesAndCallsRepo() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.toggleGlobal()  // enable first
        vm.toggleGlobal()  // now disable
        assertFalse(vm.globalEnabled.value)
        coVerify { mockRepo.setHideDbg(false, null) }
    }

    @Test
    fun toggleGlobal_revertsOnFailure() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.failure(RuntimeException("denied"))
        vm.toggleGlobal()
        assertFalse(vm.globalEnabled.value)  // reverted back to false
        assertNotNull(vm.error.value)
    }

    @Test
    fun addUid_addsToListOnSuccess() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.addUid(1000)
        assertEquals(listOf(1000), vm.uidRules.value)
        coVerify { mockRepo.setHideDbg(true, 1000) }
    }

    @Test
    fun addUid_doesNotAddDuplicate() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.addUid(1000)
        vm.addUid(1000)
        assertEquals(1, vm.uidRules.value.size)
    }

    @Test
    fun addUid_doesNotAddOnFailure() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.failure(RuntimeException("err"))
        vm.addUid(1000)
        assertEquals(0, vm.uidRules.value.size)
    }

    @Test
    fun removeUid_removesFromListOnSuccess() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.addUid(1000)
        vm.removeUid(1000)
        assertEquals(0, vm.uidRules.value.size)
        coVerify { mockRepo.setHideDbg(false, 1000) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.HideDbgViewModelTest"
```
Expected: FAIL — `HideDbgViewModel` not found.

- [ ] **Step 3: Create `HideDbgViewModel.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.hidedbg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository

class HideDbgViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance
) : ViewModel() {

    private val _globalEnabled = MutableStateFlow(false)
    val globalEnabled: StateFlow<Boolean> = _globalEnabled.asStateFlow()

    private val _uidRules = MutableStateFlow<List<Int>>(emptyList())
    val uidRules: StateFlow<List<Int>> = _uidRules.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun toggleGlobal() {
        val next = !_globalEnabled.value
        _globalEnabled.value = next
        viewModelScope.launch {
            repo.setHideDbg(next, null)
                .onFailure { e ->
                    _globalEnabled.value = !next  // revert
                    _error.value = e.message
                }
        }
    }

    fun addUid(uid: Int) {
        if (_uidRules.value.contains(uid)) return
        viewModelScope.launch {
            repo.setHideDbg(true, uid)
                .onSuccess { _uidRules.value = _uidRules.value + uid }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun removeUid(uid: Int) {
        viewModelScope.launch {
            repo.setHideDbg(false, uid)
                .onSuccess { _uidRules.value = _uidRules.value - uid }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun dismissError() { _error.value = null }
}
```

- [ ] **Step 4: Create `HideDbgScreen.kt`**

```kotlin
package ng1ok.yggdrasilmanager.ui.hidedbg

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun HideDbgScreen(vm: HideDbgViewModel = viewModel()) {
    val globalEnabled by vm.globalEnabled.collectAsState()
    val uidRules by vm.uidRules.collectAsState()
    val error by vm.error.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            text = { Text(msg) }
        )
    }

    if (showAddDialog) {
        AddUidDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { uid -> vm.addUid(uid); showAddDialog = false }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add UID rule")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                GlobalSwitchCard(enabled = globalEnabled, onToggle = vm::toggleGlobal)
            }
            if (uidRules.isNotEmpty()) {
                item {
                    Text(
                        "Per-UID Rules",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(uidRules) { uid ->
                    UidRuleCard(uid = uid, onRemove = { vm.removeUid(uid) })
                }
            }
        }
    }
}

@Composable
private fun GlobalSwitchCard(enabled: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hide Debugger", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Globally hide debugger presence from all processes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun UidRuleCard(uid: Int, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("UID: $uid", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove UID rule")
            }
        }
    }
}

@Composable
private fun AddUidDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var uidText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add UID Rule") },
        text = {
            OutlinedTextField(
                value = uidText,
                onValueChange = { uidText = it },
                label = { Text("UID") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                uidText.toIntOrNull()?.let { onConfirm(it) }
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
gradlew :app:test --tests "ng1ok.yggdrasilmanager.HideDbgViewModelTest"
```
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg app/src/test/kotlin
git commit -m "feat: HideDbgScreen and HideDbgViewModel"
```

---

## Self-Review

**Spec coverage check:**
- [x] Task 1: gradle/libs.versions.toml — all deps pinned
- [x] Task 2: YggdrasilNative.kt + YggdrasilNativeApi interface — all 6 JNI methods
- [x] Task 3: ygg_jni.cpp — checkAvailable, hwbpObserve, hwbpUnobserve, hideDbg, hwbpStartListen, hwbpStopListen
- [x] Task 4: YggdrasilRepository — all suspend funs, SharedFlow, lazy start/stop
- [x] Task 5: NavGraph + BottomNavItem — 2-tab bottom nav, 4 routes
- [x] Task 6: HomeScreen + HomeViewModel — availability check, root status
- [x] Task 7: FeaturesScreen — extensible FeatureItem registry
- [x] Task 8: HwbpScreen + HwbpViewModel — Observe tab, Events tab (max 200), AddObserveSheet
- [x] Task 9: HideDbgScreen + HideDbgViewModel — global switch, per-UID rules

**Placeholder scan:** None found.

**Type consistency check:**
- `HwbpObserveParams(pid, targetAddr, mode, len, flags, stackSize)` — consistent across Tasks 2, 4, 8
- `HwbpEvent(seq, dropped, tid, tgid, comm, mode, len, flags, watchedAddr, pc, sp, regs)` — consistent across Tasks 2, 4, 8
- `YggdrasilRepository.hwbpObserve(params: HwbpObserveParams): Result<Unit>` — Tasks 4, 8
- `YggdrasilRepository.hwbpUnobserve(pid, targetAddr): Result<Unit>` — Tasks 4, 8
- `YggdrasilRepository.setHideDbg(enable: Boolean, uid: Int?): Result<Unit>` — Tasks 4, 9
- `YggdrasilRepository.startEventStream()` / `stopEventStream()` — Tasks 4, 8
