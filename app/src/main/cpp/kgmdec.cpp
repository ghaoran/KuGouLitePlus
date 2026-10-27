// 借酷狗自己的 libjengine.so 解密 mflac/kgg。
// 关键：KGG 密钥由酷狗 infra 层结合本机机器码推导，外部无法复现，
// 因此不重写算法，直接 dlopen 它自己的解密器。
//
// 反汇编确认的签名：
//   CreateDecryption(const char* ekey, int len) -> StreamCencrypt*
//   Decrypt(StreamCencrypt* self, uint64_t offset, char* buf, int len)
//
// ⚠️ 参数顺序（0x130b14 StreamDecrypt 反汇编确认，2026-10）：
//      x0 = this(StreamCencrypt*)
//      x1 = offset  (u64，真实文件内偏移，用于生成密钥流位置)
//      x2 = buf     (char*)
//      x3 = len     (int)
//   旧版本按 (self, buf, len, offset) 传参 -> x1/x2/x3 全错位，
//   函数内部把 buf 指针当 offset、把 len 当指针写 -> 静默不改数据或崩溃。
//   这是「调用成功但输出仍非 fLaC」的真正原因。
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>

#define TAG "KgmDecNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef void* (*CreateDecryptionFn)(const char*, int);
typedef int   (*DecryptFn)(void*, unsigned long long, char*, int);
typedef void  (*DestroyFn)(void*);

static CreateDecryptionFn g_create = nullptr;
static DecryptFn         g_decrypt = nullptr;
static DestroyFn         g_destroy = nullptr;
static bool              g_tried = false;

static bool loadEngine() {
    if (g_create) return true;
    if (g_tried) return false;
    g_tried = true;

    const char* names[] = {
        "libjengine.so",
        "libkugouplayer.so",
        "libsvplayer.so",
        nullptr
    };
    for (int i = 0; names[i]; i++) {
        void* h = dlopen(names[i], RTLD_NOW);
        if (!h) { LOGI("dlopen %s failed: %s", names[i], dlerror()); continue; }
        void* c = dlsym(h, "CreateDecryption");
        void* d = dlsym(h, "Decrypt");
        if (c && d) {
            g_create  = (CreateDecryptionFn)c;
            g_decrypt = (DecryptFn)d;
            g_destroy = (DestroyFn)dlsym(h, "DestroyDecryption");
            LOGI("engine loaded from %s create=%p dec=%p", names[i], c, d);
            return true;
        }
        LOGI("%s 无解密符号", names[i]);
    }
    LOGE("no engine found");
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bldf1_kugoulite_plus_NativeH_nativeSetup(JNIEnv*, jobject) {
    return loadEngine() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bldf1_kugoulite_plus_NativeH_nativeCreate(JNIEnv* env, jobject, jstring ekey) {
    if (!loadEngine() || !ekey) return 0;
    const char* k = env->GetStringUTFChars(ekey, nullptr);
    if (!k) return 0;
    int len = (int)strlen(k);
    void* ctx = g_create(k, len);
    env->ReleaseStringUTFChars(ekey, k);
    int klen = ctx ? *(int*)((char*)ctx + 8) : -1;
    LOGI("CreateDecryption(ekeyLen=%d) -> %p  derivedKeyLen=%d branch=%s",
         len, ctx, klen,
         klen >= 0x12D ? "ProcessByRC4" : "MapCipher");
    LOGI("CreateDecryption(%d) -> %p", len, ctx);
    return (jlong)(intptr_t)ctx;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bldf1_kugoulite_plus_NativeH_nativeDecrypt(JNIEnv* env, jobject, jlong handle,
                                                    jbyteArray buf, jint off, jint len, jlong pos) {
    if (!handle || !buf) return -1;
    jbyte* p = env->GetByteArrayElements(buf, nullptr);
    if (!p) return -1;
    // 顺序: (self, offset, buf, len)
    int rc = g_decrypt((void*)(intptr_t)handle, (unsigned long long)pos,
                       (char*)p + off, (int)len);
    env->ReleaseByteArrayElements(buf, p, 0);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bldf1_kugoulite_plus_NativeH_nativeFree(JNIEnv*, jobject, jlong handle) {
    if (handle && g_destroy) g_destroy((void*)(intptr_t)handle);
}
