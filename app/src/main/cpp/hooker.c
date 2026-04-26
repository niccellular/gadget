#include <jni.h>
#include <string.h>
#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>
#include <errno.h>

#define TAG "Gadget"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * ART method replacement hooking.
 *
 * Instead of converting Java methods to native (which breaks VDEX
 * pre-compiled callers), we swap the ArtMethod contents between the
 * target method and a Java replacement method that returns true.
 *
 * The key fields to copy from the replacement to the target:
 *   - data_ (offset 16, 8 bytes on arm64) — points to code item
 *   - entry_point_from_quick_compiled_code_ (offset 24, 8 bytes)
 *
 * We preserve declaring_class_, access_flags_, dex indices from the
 * original so ART's metadata stays consistent.
 */

static int art_method_size = 0;

/*
 * Determine ArtMethod size by looking at two consecutive methods
 * in the same class (they're laid out contiguously in memory).
 */
static int detect_art_method_size(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "com/atakmap/android/gadget/plugin/Gadget");
    if (!cls) {
        LOGE("can't find Gadget class for size detection");
        return 0;
    }

    /* Get two known methods from Gadget class */
    jmethodID m1 = (*env)->GetStaticMethodID(env, cls, "alwaysTrue1",
        "(Landroid/content/Context;Ljava/lang/String;)Z");
    jmethodID m2 = (*env)->GetStaticMethodID(env, cls, "alwaysTrue2",
        "(Landroid/content/Context;Ljava/lang/String;)Z");

    if (!m1 || !m2) {
        LOGE("can't find marker methods for size detection");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        return 0;
    }

    int size = (int)((uint8_t *)m2 - (uint8_t *)m1);
    if (size < 0) size = -size;
    LOGI("ArtMethod size = %d bytes (m1=%p, m2=%p)", size, m1, m2);
    return size;
}

/*
 * Replace target method's executable fields with those from the
 * replacement method. Preserves metadata (declaring class, access
 * flags, dex indices).
 *
 * On ARM64 ArtMethod layout:
 *   [0-3]   declaring_class_  (preserve)
 *   [4-7]   access_flags_     (preserve)
 *   [8-11]  dex fields        (preserve)
 *   [12-15] dex fields        (preserve)
 *   [16+]   data_ + entry_point (REPLACE with replacement's)
 */
static int hook_method(JNIEnv *env, jclass targetClass, const char *methodName,
                       jmethodID replacement) {
    LOGD("hook_method: looking up %s", methodName);

    jmethodID target = (*env)->GetStaticMethodID(env, targetClass, methodName,
        "(Landroid/content/Context;Ljava/lang/String;)Z");
    if (!target) {
        LOGE("hook_method: method not found: %s", methodName);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        return -1;
    }
    LOGD("hook_method: target=%p, replacement=%p", target, replacement);

    uint8_t *dst = (uint8_t *)target;
    uint8_t *src = (uint8_t *)replacement;

    /* Make target writable */
    long pageSize = sysconf(_SC_PAGESIZE);
    void *pageStart = (void *)((uintptr_t)dst & ~(pageSize - 1));
    if (mprotect(pageStart, pageSize * 2, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("hook_method: mprotect failed: %s", strerror(errno));
        return -2;
    }

    /* Log before state */
    LOGD("hook_method: BEFORE target:");
    for (int i = 0; i < art_method_size && i < 40; i += 4) {
        LOGD("  [%2d] 0x%08x", i, *(uint32_t *)(dst + i));
    }
    LOGD("hook_method: replacement:");
    for (int i = 0; i < art_method_size && i < 40; i += 4) {
        LOGD("  [%2d] 0x%08x", i, *(uint32_t *)(src + i));
    }

    /*
     * Copy only the executable fields (data_ + entry_point) starting
     * at offset 16. Preserve offsets 0-15:
     *   [0-3]   declaring_class_ — must stay as AtakPluginRegistry or
     *           ART's method resolution fails (NoSuchMethodError)
     *   [4-7]   access_flags_ — must match original method type
     *   [8-15]  dex_method_index, dex_code_item_offset, etc.
     *
     * The replacement method (alwaysTrue1) is trivial: just
     * "return true" — no string refs, no method calls, no field
     * access. Its bytecode (const/4 + return) needs zero DEX
     * resolution, so running it against the target's DexCache is safe.
     */
    int copyStart = 16;
    int copyLen = art_method_size - copyStart;
    if (copyLen > 0) {
        memcpy(dst + copyStart, src + copyStart, copyLen);
        LOGD("hook_method: copied %d bytes from offset %d", copyLen, copyStart);
    }

    /* Log after state */
    LOGD("hook_method: AFTER target:");
    for (int i = 0; i < art_method_size && i < 40; i += 4) {
        LOGD("  [%2d] 0x%08x", i, *(uint32_t *)(dst + i));
    }

    LOGI("hook_method: %s hooked", methodName);
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_atakmap_android_gadget_plugin_Gadget_nativeHook(
        JNIEnv *env, jclass gadgetClass, jclass registryClass) {

    LOGI("nativeHook: called");

    /* Log target class name */
    jclass classClass = (*env)->GetObjectClass(env, registryClass);
    jmethodID getName = (*env)->GetMethodID(env, classClass, "getName",
        "()Ljava/lang/String;");
    if (getName) {
        jstring name = (*env)->CallObjectMethod(env, registryClass, getName);
        if (name) {
            const char *s = (*env)->GetStringUTFChars(env, name, NULL);
            LOGI("nativeHook: target = %s", s ? s : "(null)");
            if (s) (*env)->ReleaseStringUTFChars(env, name, s);
        }
    }

    /* Detect ArtMethod size */
    art_method_size = detect_art_method_size(env);
    if (art_method_size <= 0 || art_method_size > 128) {
        LOGE("nativeHook: invalid ArtMethod size: %d", art_method_size);
        return JNI_FALSE;
    }

    /* Get the replacement method — a Java method that returns true
     * with the same signature as verifySignature/verifyTrust */
    jmethodID replacement = (*env)->GetStaticMethodID(env, gadgetClass,
        "alwaysTrue1", "(Landroid/content/Context;Ljava/lang/String;)Z");
    if (!replacement) {
        LOGE("nativeHook: can't find alwaysTrue1");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        return JNI_FALSE;
    }

    int r1 = hook_method(env, registryClass, "verifySignature", replacement);
    LOGI("nativeHook: verifySignature = %d", r1);

    int r2 = hook_method(env, registryClass, "verifyTrust", replacement);
    LOGI("nativeHook: verifyTrust = %d", r2);

    if (r1 == 0 && r2 == 0) {
        LOGI("nativeHook: ALL HOOKS INSTALLED");
        return JNI_TRUE;
    }

    LOGE("nativeHook: FAILED: r1=%d r2=%d", r1, r2);
    return JNI_FALSE;
}
