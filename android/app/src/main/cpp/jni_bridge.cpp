// jni_bridge.cpp -- JNI 桥接：把 Spektrafilm 引擎 C ABI 暴露给 Kotlin/Compose。
//
// 对应 C 接口：engine/include/spektrafilm/spk_engine.h（全程 noexcept，错误=负 spk_status + spk_last_error）
// 设计原则：
//   - 引擎/session 句柄以 jlong（指针）跨边界，Kotlin 侧只保存数值，不做解引用。
//   - 输入像素由 Kotlin 提供 float[]，在此拷贝为 spk_image。
//   - 渲染结果 rgba16 以 jshortArray 返回（u16），Kotlin 侧转成 Bitmap/纹理。
//   - 参数、能力、错误一律为 JSON 字符串。
//   - 资源目录（resources_dir）承载 SPIR-V + 胶片/相纸 profile。
#include <jni.h>
#include <string.h>
#include <cstdlib>
#include <spektrafilm/spk_engine.h>

// ---------------------------------------------------------------------------
// 辅助
// ---------------------------------------------------------------------------
static jstring to_jstring(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s ? s : "");
}
static const char* from_jstring(JNIEnv* env, jstring js) {
    if (!js) return nullptr;
    return env->GetStringUTFChars(js, nullptr);
}
static void release_jstring(JNIEnv* env, jstring js, const char* p) {
    if (js && p) env->ReleaseStringUTFChars(js, p);
}
static spk_engine* eng(jlong p) { return reinterpret_cast<spk_engine*>(p); }
static spk_session* sess(jlong p) { return reinterpret_cast<spk_session*>(p); }

#define CHECK_NULL_ENGINE(env, p, ret) \
    if (!(p)) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "engine is null"); return (ret); }
#define CHECK_NULL_SESSION(env, p, ret) \
    if (!(p)) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "session is null"); return (ret); }

// ---------------------------------------------------------------------------
// engine 生命周期
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_spektra_android_SpektraEngine_nativeCreate(JNIEnv* env, jobject,
                                                    jstring resourcesDir) {
    const char* dir = from_jstring(env, resourcesDir);
    spk_engine* e = spk_engine_create(dir ? dir : "", nullptr); // Vulkan 后端 device 必须为 NULL
    release_jstring(env, resourcesDir, dir);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_spektra_android_SpektraEngine_nativeDestroy(JNIEnv* env, jobject, jlong enginePtr) {
    spk_engine* e = eng(enginePtr);
    if (e) spk_engine_destroy(e);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeCapabilities(JNIEnv* env, jobject, jlong enginePtr) {
    CHECK_NULL_ENGINE(env, enginePtr, nullptr)
    return to_jstring(env, spk_capabilities(eng(enginePtr)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeParamsSchema(JNIEnv* env, jobject, jlong enginePtr) {
    CHECK_NULL_ENGINE(env, enginePtr, nullptr)
    return to_jstring(env, spk_params_schema(eng(enginePtr)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeLastError(JNIEnv* env, jobject, jlong enginePtr) {
    // spk_last_error 返回线程局部全局错误（不依赖 engine 句柄），
    // 因此 spk_engine_create 失败后传 NULL 也能拿到真实失败原因。
    return to_jstring(env, spk_last_error(eng(enginePtr)));
}

// ---------------------------------------------------------------------------
// session：打开一帧
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_spektra_android_SpektraEngine_nativeOpen(JNIEnv* env, jobject, jlong enginePtr,
                                                  jfloatArray pixels, jint w, jint h, jint channels,
                                                  jstring paramsJson) {
    CHECK_NULL_ENGINE(env, enginePtr, 0L)
    spk_image img{};
    jfloat* p = env->GetFloatArrayElements(pixels, nullptr);
    if (!p) return 0;
    img.data = p; img.width = (uint32_t)w; img.height = (uint32_t)h;
    img.channels = (uint32_t)channels;
    const char* pj = from_jstring(env, paramsJson);
    char* out_json = nullptr;
    spk_session* s = spk_open(eng(enginePtr), &img, pj, &out_json);
    release_jstring(env, paramsJson, pj);
    if (out_json) spk_string_free(out_json);
    env->ReleaseFloatArrayElements(pixels, p, JNI_ABORT);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_spektra_android_SpektraEngine_nativeSessionRelease(JNIEnv* env, jobject, jlong sessionPtr) {
    spk_session* s = sess(sessionPtr);
    if (s) spk_session_release(s);
}

// ---------------------------------------------------------------------------
// 参数与配光（JSON）
// ---------------------------------------------------------------------------
static jstring run_json_string(JNIEnv* env, spk_status st, char* out_json, jlong enginePtr) {
    jstring r = to_jstring(env, out_json ? out_json : "");
    if (out_json) spk_string_free(out_json);
    if (st != SPK_OK) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      spk_last_error(eng(enginePtr)));
    }
    return r;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeSetParams(JNIEnv* env, jobject, jlong sessionPtr,
                                                       jstring deltaJson) {
    CHECK_NULL_SESSION(env, sessionPtr, nullptr)
    const char* d = from_jstring(env, deltaJson);
    char* out = nullptr;
    spk_status st = spk_set_params(sess(sessionPtr), d, &out);
    release_jstring(env, deltaJson, d);
    return run_json_string(env, st, out, 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeGetParams(JNIEnv* env, jobject, jlong sessionPtr) {
    CHECK_NULL_SESSION(env, sessionPtr, nullptr)
    char* out = nullptr;
    spk_status st = spk_get_params(sess(sessionPtr), &out);
    return run_json_string(env, st, out, 0);
}

// overscan 布局诊断：把 canvas/frame/mm_per_px/vertical/gate 返回给 Kotlin，
// 供 Deyva 诊断日志面板直接显示（不依赖 adb logcat）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeOverscanLayout(JNIEnv* env, jobject, jlong sessionPtr) {
    CHECK_NULL_SESSION(env, sessionPtr, nullptr)
    char* out = nullptr;
    spk_status st = spk_overscan_geometry(sess(sessionPtr), &out);
    if (st != SPK_OK) { free(out); return to_jstring(env, spk_last_error(nullptr)); }
    jstring js = to_jstring(env, out ? out : "{\"valid\":false}");
    free(out);
    return js;
}

// 渲染进度：引擎正在跑哪个节点。spk_progress 故意不拿 session 锁（和
// spk_cancel 一样），所以渲染卡死时另一个线程也能读到 stage —— 这正是判断
// "卡在片边光栅化" 还是 "卡在 Vulkan 提交" 的唯一现场证据。
extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeProgress(JNIEnv* env, jobject, jlong sessionPtr) {
    CHECK_NULL_SESSION(env, sessionPtr, nullptr)
    char* out = nullptr;
    spk_status st = spk_progress(sess(sessionPtr), nullptr, &out);
    if (st != SPK_OK) { free(out); return to_jstring(env, spk_last_error(nullptr)); }
    jstring js = to_jstring(env, out ? out : "{}");
    free(out);
    return js;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeSolve(JNIEnv* env, jobject, jlong sessionPtr,
                                                   jstring target) {
    CHECK_NULL_SESSION(env, sessionPtr, nullptr)
    const char* t = from_jstring(env, target);
    char* out = nullptr;
    spk_status st = spk_solve(sess(sessionPtr), t ? t : "exposure", &out);
    release_jstring(env, target, t);
    return run_json_string(env, st, out, 0);
}

// ---------------------------------------------------------------------------
// 渲染：返回 rgba16 (u16 每通道，RGBA)，并附宽高
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jshortArray JNICALL
Java_com_spektra_android_SpektraEngine_nativeRender(JNIEnv* env, jobject, jlong sessionPtr,
                                                    jstring tier, jintArray outWH) {
    CHECK_NULL_SESSION(env, sessionPtr, nullptr)
    const char* t = from_jstring(env, tier);
    spk_result res{};
    spk_status st = spk_render(sess(sessionPtr), t ? t : "preview", &res);
    release_jstring(env, tier, t);

    jshortArray arr = nullptr;
    if (st == SPK_OK && res.rgba16 && res.width && res.height) {
        size_t n = (size_t)res.width * res.height * 4;
        arr = env->NewShortArray((jsize)n);
        if (arr) env->SetShortArrayRegion(arr, 0, (jsize)n,
                                          reinterpret_cast<const jshort*>(res.rgba16));
        if (outWH) {
            jint wh[2] = {(jint)res.width, (jint)res.height};
            env->SetIntArrayRegion(outWH, 0, 2, wh);
        }
        spk_result_free(&res);
    } else {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      spk_last_error(nullptr)); // 引擎级错误取最近线程
    }
    return arr;
}

// ---------------------------------------------------------------------------
// 渲染结果 -> jshortArray（rgba16）。nativeRender / nativeReprint /
// nativePreviewStockLut 共用；失败时抛 RuntimeException 并返回 nullptr。
// ---------------------------------------------------------------------------
static jshortArray result_to_shorts(JNIEnv* env, spk_status st, spk_result& res, jintArray outWH) {
    jshortArray arr = nullptr;
    if (st == SPK_OK && res.rgba16 && res.width && res.height) {
        size_t n = (size_t)res.width * res.height * 4;
        arr = env->NewShortArray((jsize)n);
        if (arr) env->SetShortArrayRegion(arr, 0, (jsize)n,
                                          reinterpret_cast<const jshort*>(res.rgba16));
        if (outWH) {
            jint wh[2] = {(jint)res.width, (jint)res.height};
            env->SetIntArrayRegion(outWH, 0, 2, wh);
        }
        spk_result_free(&res);
    } else {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      spk_last_error(nullptr));
    }
    return arr;
}

// [r11 · 第 0 批] PRINT 层重印：只改了相纸 / 放大机 / 光泽时，复用缓存的负片
// 直接重新印相纸，跳过重冲（把"切相纸慢"的根因解掉）。
// 第一次渲染之前没有负片缓存，会返回失败 —— 调用方回退到 spk_render。
extern "C" JNIEXPORT jshortArray JNICALL
Java_com_spektra_android_SpektraEngine_nativeReprint(JNIEnv* env, jobject, jlong sessionPtr,
                                                     jstring tier, jintArray outWH) {
    if (!sessionPtr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "session is null");
        return nullptr;
    }
    const char* t = from_jstring(env, tier);
    spk_result res{};
    spk_status st = spk_reprint(sess(sessionPtr), t ? t : "live", &res);
    release_jstring(env, tier, t);
    return result_to_shorts(env, st, res, outWH);
}

// [r11 · 第 0 批] 取消渲染：spk_cancel 不拿 session 锁，允许在渲染进行中从另一
// 个线程调用；渲染会在下一个节点边界退出并返回 SPK_ERR_CANCELLED。
// 这是"卡死只能等 25 秒超时"的正解 —— 超时时先取消，再换闸门。
extern "C" JNIEXPORT void JNICALL
Java_com_spektra_android_SpektraEngine_nativeCancel(JNIEnv* env, jobject, jlong sessionPtr,
                                                    jstring progressId) {
    if (!sessionPtr) return;
    const char* pid = from_jstring(env, progressId);
    spk_cancel(sess(sessionPtr), pid);
    release_jstring(env, progressId, pid);
}

// [r11 · 第 0 批] 相纸 LUT 预览：把烘焙好的 8 个相纸预览表套在缓存的负片上。
// **不在主渲染路径里用**（LUT 是近似预览，和精确渲染会有可见色差），只作为
// 能力预留 / 诊断入口：Kotlin 侧当前只在需要快速比对时可选调用。
extern "C" JNIEXPORT jshortArray JNICALL
Java_com_spektra_android_SpektraEngine_nativePreviewStockLut(JNIEnv* env, jobject, jlong sessionPtr,
                                                             jstring printStock, jstring tier,
                                                             jintArray outWH) {
    if (!sessionPtr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "session is null");
        return nullptr;
    }
    const char* ps = from_jstring(env, printStock);
    const char* t = from_jstring(env, tier);
    spk_result res{};
    char* info = nullptr;
    spk_status st = spk_preview_stock_lut(sess(sessionPtr), ps ? ps : "", t ? t : "live", &res, &info);
    release_jstring(env, printStock, ps);
    release_jstring(env, tier, t);
    if (info) spk_string_free(info);
    return result_to_shorts(env, st, res, outWH);
}

// 哪些相纸有随包烘焙的 LUT（print_luts.json 原样）。没有 LUT 的相纸不能走上面那条。
extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativePrintLutCatalog(JNIEnv* env, jobject, jlong enginePtr) {
    CHECK_NULL_ENGINE(env, enginePtr, nullptr)
    return to_jstring(env, spk_print_lut_catalog(eng(enginePtr)));
}

// ---------------------------------------------------------------------------
// 配光预热 / 内存报告
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeWarmUp(JNIEnv* env, jobject, jlong enginePtr,
                                                    jstring filmStock, jstring printStock) {
    CHECK_NULL_ENGINE(env, enginePtr, nullptr)
    const char* f = from_jstring(env, filmStock);
    const char* p = from_jstring(env, printStock);
    char* out = nullptr;
    spk_status st = spk_warm_up(eng(enginePtr), f, p, &out);
    release_jstring(env, filmStock, f);
    release_jstring(env, printStock, p);
    return run_json_string(env, st, out, enginePtr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeMemoryReport(JNIEnv* env, jobject, jlong enginePtr) {
    CHECK_NULL_ENGINE(env, enginePtr, nullptr)
    return to_jstring(env, spk_memory_report(eng(enginePtr)));
}
