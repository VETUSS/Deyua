// jni_raw.cpp -- RAW 解码 JNI 桥接：把引擎 spk::io::raw_decoder 暴露给 Kotlin。
//
// 独立于渲染引擎核心（spektrafilm_engine.so 不含 spk_image_io/raw_decoder）。
// 输入：RAW 文件路径（DNG/CR2/ARW/NEF 等，Kotlin 侧已把 content Uri 拷贝到缓存文件）。
// 输出：Packed top-down RGB float（linear LibRaw ProPhoto, [0,1]），并回填宽高。
// 失败：返回 null，错误经 nativeRawLastError() 读取。
#include <jni.h>
#include <string>
#include <vector>
#include <filesystem>
#include "io/raw_decoder.hpp"

static std::string g_raw_last_error;

// 解码上限：长边 4096（预览 + 常规导出够用）。全尺寸高像素 RAW(48MP DNG)
// 的 float 输出会 OOM 闪退，故解码时即用 LibRaw half_size 缩到约一半边长。
static constexpr unsigned kRawMaxLongEdge = 4096;

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeRawLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_raw_last_error.c_str());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_spektra_android_SpektraEngine_nativeDecodeRaw(JNIEnv* env, jobject,
                                                       jstring path, jintArray outSize) {
    const char* p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (!p) return nullptr;
    std::filesystem::path src(p);
    env->ReleaseStringUTFChars(path, p);

    spk::io::DecodedRaw raw;
    std::string err;
    if (!spk::io::decode_raw_scaled(src, kRawMaxLongEdge, raw, err)) {
        g_raw_last_error = err;
        return nullptr;
    }
    g_raw_last_error.clear();

    const jsize n = static_cast<jsize>(raw.width) * static_cast<jsize>(raw.height) * 3;
    if (n <= 0 || raw.rgb.size() < static_cast<size_t>(n)) {
        g_raw_last_error = "raw decode returned empty image";
        return nullptr;
    }
    jfloatArray arr = env->NewFloatArray(n);
    if (arr) env->SetFloatArrayRegion(arr, 0, n, raw.rgb.data());
    if (outSize && env->GetArrayLength(outSize) >= 2) {
        jint sz[2] = {static_cast<jint>(raw.width), static_cast<jint>(raw.height)};
        env->SetIntArrayRegion(outSize, 0, 2, sz);
    }
    return arr;
}
