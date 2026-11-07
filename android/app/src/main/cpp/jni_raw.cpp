// jni_raw.cpp -- RAW 解码 JNI 桥接：把引擎 spk::io::raw_decoder 暴露给 Kotlin。
//
// 独立编译成 libjni_raw.so（引擎核心不含 spk_image_io/raw_decoder），但它同时
// 链接 spektrafilm_engine.so：解码后的像素可以**在 native 侧直接开成会话**，
// 不必先变成一份 Java float[]。这一条是 r40 最重要的结构变化 ——
// 45 MP 的 float RGB 是 440 MB，Java 堆根本放不下，旧路径被迫在解码时就砍半，
// 「导出不是全分辨率」的根因其实在导入这一端。
//
// 两条出口：
//   nativeDecodeRawOpen  → 解码 + spk_open，返回 session（主流程走这条）
//   nativeDecodeRaw      → 解码 + 返回 jfloatArray（回退路径，小图/调试）
//
// 解码策略（r40）：
//   1) 先只读 RAW 头拿厂商、机型、原生尺寸与实际解码器（不 unpack）；
//   2) 原生 ≤40 MP 且系统可用内存充足时，走全尺寸 AHD，再按**任意比例**做
//      面积平均降到目标长边 —— 旧路径的 LibRaw half_size 只能 1/2、1/4，
//      7168 长边会被砍到 3584，比 128 MiB 预算对应的 4096 还少 12.5%；
//   3) 超大传感器（>40 MP）或可用内存不足时，仍走旧的 half_size 路径，
//      与 r39 行为一致，不会为了多几百万像素把进程交给 LMK；
//   4) 降采样**之前**裁掉右/下的光学黑遮罩边（LibRaw 未收录机型会把整帧
//      传感器连同遮罩一起 demosaic，就是导出图右边/底部那条黑边）。
#include <jni.h>
#include <libraw/libraw.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

#include "io/raw_decoder.hpp"
#include "spektrafilm/spk_engine.h"

static std::string g_raw_last_error;

// 超过这个像素数就不再尝试全尺寸 AHD。36.7 MP 的 A7 系列全尺寸解码在 native
// 侧的峰值约 1.2 GB（float 输出 + LibRaw 的 16bit 中间图 + 处理缓冲），60 MP
// 以上没有余量，交给 LibRaw 的 half_size 更稳。
static constexpr std::uint64_t kFullDecodeMaxPixels = 40ull * 1000ull * 1000ull;
// 全尺寸解码还需要一份「目标尺寸」的缓冲做面积平均，可用内存低于这个数就放弃。
static constexpr std::uint64_t kFullDecodeMinFreeRam = 2500ull * 1024ull * 1024ull;
// 旧路径的固定上限（与 r39 一致），仅在放弃全尺寸解码时使用。
static constexpr unsigned kLegacyEdge = 4096;
// 导入时顺带生成的「预览小图」长边：缩略图源与原图对比都用它，不必再往堆上
// 放一份全幅 float —— 45 MP 的那一份是 440 MB。
static constexpr unsigned kPreviewEdge = 1600;

/** 只读 RAW 头得到的信息；解码与诊断日志共用一次 open_file。 */
struct RawHeader {
    bool ok = false;
    std::uint32_t width = 0, height = 0;          // 有效成像（未收录机型 = 整帧）
    std::uint32_t raw_width = 0, raw_height = 0;  // 传感器帧
    std::string make, model, decoder;
};

static RawHeader probe_header(const std::filesystem::path& src) {
    RawHeader h;
    try {
        LibRaw probe(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
        if (probe.open_file(src.c_str()) != LIBRAW_SUCCESS) return h;
        h.width = static_cast<std::uint32_t>(probe.imgdata.sizes.width);
        h.height = static_cast<std::uint32_t>(probe.imgdata.sizes.height);
        h.raw_width = static_cast<std::uint32_t>(probe.imgdata.sizes.raw_width);
        h.raw_height = static_cast<std::uint32_t>(probe.imgdata.sizes.raw_height);
        if (probe.imgdata.idata.make[0]) h.make = probe.imgdata.idata.make;
        if (probe.imgdata.idata.model[0]) h.model = probe.imgdata.idata.model;
        if (const char* name = probe.unpack_function_name()) h.decoder = name;
        h.ok = h.width > 0 && h.height > 0;
        probe.recycle();
    } catch (...) {
        h.ok = false;
    }
    return h;
}

/** /proc/meminfo 的 MemAvailable；拿不到时按「不充足」处理。 */
static std::uint64_t available_ram_bytes() {
    std::FILE* f = std::fopen("/proc/meminfo", "r");
    if (!f) return 0;
    char line[256];
    unsigned long long kb = 0;
    while (std::fgets(line, sizeof line, f)) {
        if (std::sscanf(line, "MemAvailable: %llu kB", &kb) == 1) break;
    }
    std::fclose(f);
    return static_cast<std::uint64_t>(kb) * 1024ull;
}

/**
 * 面积平均降采样到任意目标长边，结果写进独立缓冲（源图不动）。
 *
 * 目标像素 (x,y) 覆盖源图的一个矩形区间，对区间内所有源像素取均值。整数边界
 * 保证不重不漏，也不会像 2 的幂 box 那样把 7168 直接砍成 3584。
 */
static void box_downsample_into(const std::vector<float>& src, std::uint32_t sw, std::uint32_t sh,
                                std::vector<float>& dst, std::uint32_t& dw, std::uint32_t& dh,
                                unsigned max_edge) {
    const std::uint32_t long_edge = std::max(sw, sh);
    if (max_edge == 0 || long_edge == 0 || long_edge <= max_edge) return;

    const double s = static_cast<double>(max_edge) / static_cast<double>(long_edge);
    const int nw = std::max(1, static_cast<int>(std::lround(sw * s)));
    const int nh = std::max(1, static_cast<int>(std::lround(sh * s)));
    if (nw == static_cast<int>(sw) && nh == static_cast<int>(sh)) return;

    dst.resize(static_cast<std::size_t>(nw) * nh * 3);
    const int ow = static_cast<int>(sw);
    const int oh = static_cast<int>(sh);
    for (int y = 0; y < nh; ++y) {
        const int sy0 = static_cast<int>(static_cast<std::int64_t>(y) * oh / nh);
        int sy1 = static_cast<int>(static_cast<std::int64_t>(y + 1) * oh / nh);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        if (sy1 > oh) sy1 = oh;
        float* out_row = dst.data() + static_cast<std::size_t>(y) * nw * 3;
        for (int x = 0; x < nw; ++x) {
            const int sx0 = static_cast<int>(static_cast<std::int64_t>(x) * ow / nw);
            int sx1 = static_cast<int>(static_cast<std::int64_t>(x + 1) * ow / nw);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            if (sx1 > ow) sx1 = ow;
            double r = 0.0, g = 0.0, b = 0.0;
            int cnt = 0;
            for (int sy = sy0; sy < sy1; ++sy) {
                const float* row = src.data() + static_cast<std::size_t>(sy) * ow * 3;
                for (int sx = sx0; sx < sx1; ++sx) {
                    const float* p = row + static_cast<std::size_t>(sx) * 3;
                    r += p[0]; g += p[1]; b += p[2]; ++cnt;
                }
            }
            const float inv = cnt ? static_cast<float>(1.0 / static_cast<double>(cnt)) : 0.0f;
            float* o = out_row + static_cast<std::size_t>(x) * 3;
            o[0] = static_cast<float>(r * inv);
            o[1] = static_cast<float>(g * inv);
            o[2] = static_cast<float>(b * inv);
        }
    }
    dw = static_cast<std::uint32_t>(nw);
    dh = static_cast<std::uint32_t>(nh);
}

/** 就地缩到 max_edge（不缩时原样返回，不做多余拷贝）。 */
static void box_downscale(std::vector<float>& rgb, std::uint32_t& w, std::uint32_t& h,
                          unsigned max_edge) {
    const std::uint32_t long_edge = std::max(w, h);
    if (max_edge == 0 || long_edge == 0 || long_edge <= max_edge) return;
    std::vector<float> dst;
    std::uint32_t nw = w, nh = h;
    box_downsample_into(rgb, w, h, dst, nw, nh, max_edge);
    if (dst.empty()) return;
    rgb.swap(dst);
    w = nw;
    h = nh;
}

/**
 * 裁掉 RAW 帧里右/下的非成像遮罩边（就地，无第二份大缓冲）。
 *
 * LibRaw 0.22.2 只对机型表里的机身下「有效成像区」裁剪规则。未收录的新机
 * 走 identify 的兜底 `width = raw_width`，整块传感器帧（含右侧/下方光学黑
 * 遮罩行列）都被 demosaic 进输出，扣黑电平后 ≈0，冲印出来就是纸黑偏蓝的
 * 一条边 —— 用户看到的是「导出图右下有黑边」。
 *
 * 三重护栏，宁可不裁也不能裁到真实画面：
 *  1) 列/行**均值** ≤ kDark 才算遮罩：均值对热像素、整行读出噪声不敏感；
 *  2) 边界取「后缀里 ≥90% 是遮罩」的最长处，连续 4 列/行不暗即停；右、下
 *     必须**同时**命中、各 ≥0.5%，最多各 12%；
 *  3) 遮罩带必须比紧邻画面暗 4 倍以上，且紧邻画面本身有信号（>4*kDark）——
 *     整幅极暗的夜景因此不会被误当成遮罩。
 */
struct MaskedBorderTrim {
    std::uint32_t cut_x = 0, cut_y = 0;
    bool applied = false;
};

/** 从最外侧往里，找出「后缀遮罩密度 ≥90%」的最长裁切量。 */
static std::uint32_t masked_suffix_boundary(const std::vector<std::uint8_t>& masked,
                                            std::uint32_t min_cut) {
    std::uint32_t dark = 0, cut = 0, streak = 0;
    for (std::uint32_t k = 0; k < masked.size(); ++k) {
        const std::uint32_t n = k + 1;
        if (masked[k]) { ++dark; streak = 0; }
        else { ++streak; if (streak >= 4) break; }
        if (n >= min_cut && dark * 100u >= n * 90u) cut = n;
    }
    return cut;
}

static MaskedBorderTrim trim_masked_border(std::vector<float>& rgb,
                                           std::uint32_t& w, std::uint32_t& h) {
    MaskedBorderTrim result;
    if (w < 64 || h < 64) return result;
    if (rgb.size() < static_cast<std::size_t>(w) * h * 3) return result;

    // LibRaw 输出是 16bit/65535 的线性值。遮罩像素扣掉黑电平后贴着 0，
    // 0.002 ≈ 131 码值；真实暗部不会整列/整行都低于它。
    constexpr double kDark = 0.002;
    const std::uint32_t max_cx = std::max(1u, w * 12u / 100u);
    const std::uint32_t max_cy = std::max(1u, h * 12u / 100u);
    const std::uint32_t min_cx = std::max(1u, w / 200u);  // 0.5%
    const std::uint32_t min_cy = std::max(1u, h / 200u);

    // 右条带列均值：外层走行（row-major，缓存友好），一次扫完所有候选列。
    std::vector<double> col_sum(max_cx, 0.0);
    std::uint32_t samples = 0;
    const std::uint32_t row_step = std::max(1u, h / 4096u);
    for (std::uint32_t y = 0; y < h; y += row_step) {
        const float* row = rgb.data() + static_cast<std::size_t>(y) * w * 3;
        ++samples;
        for (std::uint32_t k = 0; k < max_cx; ++k) {
            const float* p = row + static_cast<std::size_t>(w - 1u - k) * 3;
            col_sum[k] += (p[0] + p[1] + p[2]) / 3.0;
        }
    }
    if (samples == 0) return result;
    std::vector<std::uint8_t> masked_col(max_cx, 0);
    for (std::uint32_t k = 0; k < max_cx; ++k)
        masked_col[k] = (col_sum[k] / samples <= kDark) ? 1 : 0;
    const std::uint32_t cut_x = masked_suffix_boundary(masked_col, min_cx);

    // 下条带行均值：只看有效宽度（右遮罩列已排除）。
    const std::uint32_t row_w = w - cut_x;
    const std::uint32_t col_step = std::max(1u, row_w / 4096u);
    std::vector<std::uint8_t> masked_row(max_cy, 0);
    for (std::uint32_t k = 0; k < max_cy; ++k) {
        const float* row = rgb.data() + static_cast<std::size_t>(h - 1u - k) * w * 3;
        double sum = 0.0;
        std::uint32_t n = 0;
        for (std::uint32_t x = 0; x < row_w; x += col_step) {
            const float* p = row + static_cast<std::size_t>(x) * 3;
            sum += (p[0] + p[1] + p[2]) / 3.0;
            ++n;
        }
        masked_row[k] = (n > 0 && sum / n <= kDark) ? 1 : 0;
    }
    const std::uint32_t cut_y = masked_suffix_boundary(masked_row, min_cy);

    // 单边命中不动手：宁可留一条细边，也不能把真实画面裁掉。
    if (cut_x == 0 || cut_y == 0) return result;

    // 对比护栏：抽样算条带均值。
    auto region_mean = [&](std::uint32_t x0, std::uint32_t x1,
                           std::uint32_t y0, std::uint32_t y1) -> double {
        if (x1 <= x0 || y1 <= y0) return 1.0;
        const std::uint32_t ys = std::max(1u, (y1 - y0) / 2048u);
        const std::uint32_t xs = std::max(1u, (x1 - x0) / 2048u);
        double sum = 0.0;
        std::uint32_t n = 0;
        for (std::uint32_t y = y0; y < y1; y += ys) {
            const float* row = rgb.data() + static_cast<std::size_t>(y) * w * 3;
            for (std::uint32_t x = x0; x < x1; x += xs) {
                const float* p = row + static_cast<std::size_t>(x) * 3;
                sum += (p[0] + p[1] + p[2]) / 3.0;
                ++n;
            }
        }
        return n ? sum / n : 1.0;
    };
    const std::uint32_t ix0 = (w > 2u * cut_x) ? (w - 2u * cut_x) : 0u;
    const std::uint32_t iy0 = (h > 2u * cut_y) ? (h - 2u * cut_y) : 0u;
    const double mask_x = region_mean(w - cut_x, w, 0, h);
    const double inner_x = region_mean(ix0, w - cut_x, 0, h);
    const double mask_y = region_mean(0, w - cut_x, h - cut_y, h);
    const double inner_y = region_mean(0, w - cut_x, iy0, h - cut_y);
    if (!(inner_x > 4.0 * kDark && inner_y > 4.0 * kDark &&
          mask_x * 4.0 < inner_x && mask_y * 4.0 < inner_y)) return result;

    const std::uint32_t nw = w - cut_x, nh = h - cut_y;
    // 目标行号恒 <= 源行号，就地 memmove 不会覆盖尚未读的行。
    for (std::uint32_t y = 1; y < nh; ++y) {
        std::memmove(rgb.data() + static_cast<std::size_t>(y) * nw * 3,
                     rgb.data() + static_cast<std::size_t>(y) * w * 3,
                     static_cast<std::size_t>(nw) * 3 * sizeof(float));
    }
    rgb.resize(static_cast<std::size_t>(nw) * nh * 3);
    w = nw;
    h = nh;
    result.cut_x = cut_x;
    result.cut_y = cut_y;
    result.applied = true;
    return result;
}

/** 一次导入的完整结果：像素 + 三个尺寸口径。 */
struct DecodedPhoto {
    std::vector<float> rgb;
    std::uint32_t width = 0, height = 0;    // 工作尺寸（已缩到 maxEdge）
    std::uint32_t active_w = 0, active_h = 0;  // 有效成像（裁遮罩后、缩放前）
    std::uint32_t frame_w = 0, frame_h = 0;    // LibRaw 报的帧尺寸
    std::uint32_t cut_x = 0, cut_y = 0;
    bool full_decode = false;
};

/**
 * 解码并裁边、缩放。`max_pixels<=0` 表示不缩。
 *
 * 预算按**像素数**给而不是长边：同样是 3800 长边，3:2 是 9.6 MP、4:3 是 10.8 MP，
 * 差 12%。调用方只知道设备能装多少像素，比例要等 RAW 头读出来才知道，所以在
 * 这里按真实宽高比换算成长边。
 */
static unsigned edge_for_pixels(std::uint64_t max_pixels, const RawHeader& hdr) {
    if (max_pixels == 0) return 0;
    double aspect = 1.5;
    if (hdr.ok && hdr.width > 0 && hdr.height > 0 && hdr.width != hdr.height) {
        aspect = static_cast<double>(std::max(hdr.width, hdr.height)) /
                 static_cast<double>(std::min(hdr.width, hdr.height));
    }
    const double edge = std::sqrt(static_cast<double>(max_pixels) * aspect);
    if (!std::isfinite(edge) || edge < 1.0) return 0;
    return static_cast<unsigned>(std::min(edge, 16384.0));
}

static bool decode_photo(const std::filesystem::path& src, std::uint64_t max_pixels,
                         const RawHeader& hdr, DecodedPhoto& out,
                         std::vector<float>* thumb, std::uint32_t& thumb_w,
                         std::uint32_t& thumb_h, std::string& err) {
    const unsigned max_edge = edge_for_pixels(max_pixels, hdr);
    const std::uint64_t native_px =
        hdr.ok ? static_cast<std::uint64_t>(hdr.width) * hdr.height : 0ull;
    const bool known = hdr.ok && native_px > 0;
    const bool huge = !known || native_px > kFullDecodeMaxPixels;
    const bool ram_ok = available_ram_bytes() >= kFullDecodeMinFreeRam;
    const bool full_decode = !huge && ram_ok;

    spk::io::DecodedRaw raw;
    const unsigned decode_limit = full_decode ? 0u : kLegacyEdge;
    if (!spk::io::decode_raw_scaled(src, decode_limit, raw, err)) return false;
    if (raw.width == 0 || raw.height == 0 || raw.rgb.empty()) {
        err = "RAW output: decoder returned an empty frame";
        return false;
    }

    out.full_decode = full_decode;
    out.frame_w = hdr.ok ? hdr.raw_width : raw.width;
    out.frame_h = hdr.ok ? hdr.raw_height : raw.height;

    // 必须在面积平均**之前**裁：平均会把黑遮罩混进最后一列/行，事后再裁就
    // 再也分不出边界了。
    const MaskedBorderTrim trim = trim_masked_border(raw.rgb, raw.width, raw.height);
    out.active_w = raw.width;
    out.active_h = raw.height;
    out.cut_x = trim.cut_x;
    out.cut_y = trim.cut_y;

    if (thumb) {
        // 小图不能比工作图还大：小目标下按工作长边走，省一次无用的缩小。
        const unsigned edge = (max_edge > 0 && max_edge < kPreviewEdge) ? max_edge : kPreviewEdge;
        thumb_w = raw.width;
        thumb_h = raw.height;
        box_downsample_into(raw.rgb, raw.width, raw.height, *thumb, thumb_w, thumb_h, edge);
        if (thumb->empty()) {
            // 原图本来就不超过小图长边：直接给一份拷贝，调用方不必区分这两种情形。
            *thumb = raw.rgb;
            thumb_w = raw.width;
            thumb_h = raw.height;
        }
    }

    if (max_edge > 0)
        box_downscale(raw.rgb, raw.width, raw.height, max_edge);

    out.width = raw.width;
    out.height = raw.height;
    out.rgb = std::move(raw.rgb);
    return true;
}

static void fill_out_size(JNIEnv* env, jintArray out_size, const DecodedPhoto& p,
                          std::uint32_t thumb_w, std::uint32_t thumb_h) {
    const jsize len = out_size ? env->GetArrayLength(out_size) : 0;
    if (len >= 10) {
        const jint v[10] = {
            static_cast<jint>(p.width), static_cast<jint>(p.height),
            static_cast<jint>(p.active_w), static_cast<jint>(p.active_h),
            static_cast<jint>(p.frame_w), static_cast<jint>(p.frame_h),
            static_cast<jint>(p.cut_x), static_cast<jint>(p.cut_y),
            static_cast<jint>(thumb_w), static_cast<jint>(thumb_h),
        };
        env->SetIntArrayRegion(out_size, 0, 10, v);
    } else if (len >= 8) {
        const jint v[8] = {
            static_cast<jint>(p.width), static_cast<jint>(p.height),
            static_cast<jint>(p.active_w), static_cast<jint>(p.active_h),
            static_cast<jint>(p.frame_w), static_cast<jint>(p.frame_h),
            static_cast<jint>(p.cut_x), static_cast<jint>(p.cut_y),
        };
        env->SetIntArrayRegion(out_size, 0, 8, v);
    } else if (len >= 4) {
        const jint v[4] = {static_cast<jint>(p.width), static_cast<jint>(p.height),
                           static_cast<jint>(p.active_w), static_cast<jint>(p.active_h)};
        env->SetIntArrayRegion(out_size, 0, 4, v);
    } else if (len >= 2) {
        const jint v[2] = {static_cast<jint>(p.width), static_cast<jint>(p.height)};
        env->SetIntArrayRegion(out_size, 0, 2, v);
    }
}

static std::filesystem::path take_path(JNIEnv* env, jstring path) {
    if (!path) return {};
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return {};
    std::filesystem::path out(p);
    env->ReleaseStringUTFChars(path, p);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeRawLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_raw_last_error.c_str());
}

/**
 * 只读头回报「厂商 机型 | 有效尺寸 | 实际选用的 LibRaw 解码器 | arw6」。
 *
 * ARW 6.0 有三档：未压缩、Lossless(sony_arw2_load_raw)、Compressed /
 * Compressed HQ(sony_arw6_load_raw, tiff_compress=32766)。0.22.2 原本没有
 * 32766 分支，会落到 default: is_raw=0，unpack() 直接失败；这条日志让真机
 * 一眼确认文件是哪一档、backport 的解码器有没有真的被选中。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_spektra_android_SpektraEngine_nativeRawProbe(JNIEnv* env, jobject, jstring path) {
    const std::filesystem::path src = take_path(env, path);
    if (src.empty()) return nullptr;
    const RawHeader h = probe_header(src);
    if (!h.ok) return nullptr;
    std::string s = (h.make.empty() ? "?" : h.make) + " " + (h.model.empty() ? "?" : h.model);
    s += "|" + std::to_string(h.width) + "x" + std::to_string(h.height);
    s += "|" + (h.decoder.empty() ? "unknown" : h.decoder);
#ifdef SPEKTRA_ARW6_DECODER
    s += "|arw6=1";
#else
    s += "|arw6=0";
#endif
    return env->NewStringUTF(s.c_str());
}

/**
 * 解码并**直接开成引擎会话**（r40 主路径）。
 *
 * 存在的理由只有一个：45 MP 的 float RGB 是 440 MB。旧路径要把它变成 Java
 * float[] 再传回引擎，堆上就得多住一份，导入分辨率因此被堆大小而不是 GPU
 * 预算决定。这里像素从头到尾留在 native 堆，spk_open 拷进 GPU 后立刻释放。
 *
 * 返回 session 指针（0 表示失败，原因见 nativeRawLastError）。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_spektra_android_SpektraEngine_nativeDecodeRawOpen(
        JNIEnv* env, jobject, jlong engine_ptr, jstring path, jlong max_pixels,
        jstring params_json, jintArray out_size, jfloatArray thumb_out) {
    if (engine_ptr == 0) {
        g_raw_last_error = "engine is not created";
        return 0;
    }
    const std::filesystem::path src = take_path(env, path);
    if (src.empty()) {
        g_raw_last_error = "RAW open: source path is empty";
        return 0;
    }
    try {
        const RawHeader hdr = probe_header(src);
        DecodedPhoto photo;
        std::vector<float> thumb;
        std::uint32_t tw = 0, th = 0;
        std::string err;
        if (!decode_photo(src, static_cast<std::uint64_t>(max_pixels > 0 ? max_pixels : 0),
                          hdr, photo, thumb_out ? &thumb : nullptr, tw, th, err)) {
            g_raw_last_error = err.empty() ? "RAW decode failed" : err;
            return 0;
        }
        if (photo.rgb.size() < static_cast<std::size_t>(photo.width) * photo.height * 3) {
            g_raw_last_error = "RAW decode returned an incomplete frame";
            return 0;
        }

        const char* pj = params_json ? env->GetStringUTFChars(params_json, nullptr) : nullptr;
        spk_image image{};
        image.data = photo.rgb.data();
        image.width = photo.width;
        image.height = photo.height;
        image.channels = 3;
        char* reply = nullptr;
        spk_session* session = spk_open(
            reinterpret_cast<spk_engine*>(engine_ptr), &image, pj, &reply);
        if (pj) env->ReleaseStringUTFChars(params_json, pj);
        if (reply) spk_string_free(reply);
        if (!session) {
            const char* why = spk_last_error(reinterpret_cast<spk_engine*>(engine_ptr));
            g_raw_last_error = why ? why : "spk_open failed";
            return 0;
        }

        // 小图在会话建立之后才写回：先保证会话成功，失败时调用方拿到的
        // 就是「没有 session」，不会出现「小图有、图打不开」的半截状态。
        if (thumb_out && tw > 0 && th > 0) {
            const jsize cap = env->GetArrayLength(thumb_out);
            const jsize n = static_cast<jsize>(tw) * static_cast<jsize>(th) * 3;
            if (n > 0 && n <= cap) env->SetFloatArrayRegion(thumb_out, 0, n, thumb.data());
            else { tw = 0; th = 0; }
        }
        g_raw_last_error.clear();
        fill_out_size(env, out_size, photo, tw, th);
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        g_raw_last_error = std::string("RAW decode threw: ") + e.what();
        return 0;
    } catch (...) {
        g_raw_last_error = "RAW decode threw an unknown exception";
        return 0;
    }
}

/** 解码成 Java float[]（回退路径：小于堆预算的图、以及调试用）。 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_spektra_android_SpektraEngine_nativeDecodeRaw(
        JNIEnv* env, jobject, jstring path, jlong max_pixels, jintArray out_size) {
    const std::filesystem::path src = take_path(env, path);
    if (src.empty()) return nullptr;
    try {
        const RawHeader hdr = probe_header(src);
        DecodedPhoto photo;
        std::uint32_t tw = 0, th = 0;
        std::string err;
        if (!decode_photo(src, static_cast<std::uint64_t>(max_pixels > 0 ? max_pixels : 0),
                          hdr, photo, nullptr, tw, th, err)) {
            g_raw_last_error = err.empty() ? "RAW decode failed" : err;
            return nullptr;
        }
        const jsize n = static_cast<jsize>(photo.width) * static_cast<jsize>(photo.height) * 3;
        if (n <= 0 || photo.rgb.size() < static_cast<std::size_t>(n)) {
            g_raw_last_error = "RAW decode returned an empty image";
            return nullptr;
        }
        jfloatArray arr = env->NewFloatArray(n);
        if (!arr) {
            g_raw_last_error = "out of memory allocating the decoded frame";
            return nullptr;
        }
        env->SetFloatArrayRegion(arr, 0, n, photo.rgb.data());
        g_raw_last_error.clear();
        fill_out_size(env, out_size, photo, tw, th);
        return arr;
    } catch (const std::exception& e) {
        g_raw_last_error = std::string("RAW decode threw: ") + e.what();
        return nullptr;
    } catch (...) {
        g_raw_last_error = "RAW decode threw an unknown exception";
        return nullptr;
    }
}
