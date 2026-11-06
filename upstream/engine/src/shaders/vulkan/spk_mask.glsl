// spk_mask.glsl -- GLSL translation of contrast_mask.metal's helpers (RFC-024
// §5, §7.2). Transferred verbatim; only Metal -> GLSL spelling changed.
//
// The mask's coordinate is `x`: the equal-weight mean over the three paper
// channels of `log2(A_c / A_ref_c)`, where `A_ref` is the negative's own
// mid-grey through the same enlarger. `x = 0` is mid-grey.
//
// `mp` is the one parameter block both kernels read (contrast_mask.cpp builds
// it; the indices below are that file's `p[]`):
//   0..2  the enlarger's setup gain (image exposure only)
//   3..5  1 / A_ref
//   6..8  the pre-flash offset (epilogue only)
//   9 k_lo  10 h_lo  11 lo_on    -- the print-highlight branch
//   12 k_hi 13 h_hi  14 hi_on    -- the print-shadow branch
//   15 the gain limit, stops
#ifndef SPK_MASK_GLSL
#define SPK_MASK_GLSL

// The four inputs every mask kernel reads plus the parameter block. Both
// .comp files include this header and must NOT redeclare these; the ones that
// differ between the two kernels (meta, coef, out) are declared per kernel.
layout(std430, binding = 0) readonly buffer MaskCmy { float mask_cmy[]; };
layout(std430, binding = 1) readonly buffer MaskChd { float mask_chd[]; };
layout(std430, binding = 2) readonly buffer MaskBase { float mask_base[]; };
layout(std430, binding = 3) readonly buffer MaskIxs { float mask_ixs[]; };
layout(std430, binding = 4) readonly buffer MaskParams { float mp[16]; };

// 1D dispatch index -- vulkan_gpu.cpp chunks large dispatches (>~256 K pixels
// on Adreno) and hands each chunk its first element in push constant `base`.
layout(push_constant) uniform SpkDispatch { uint base; uint origin; uint read_bias; } spk_pc;
uint spk_global_index() {
    return spk_pc.base + gl_GlobalInvocationID.y * (gl_NumWorkGroups.x * gl_WorkGroupSize.x) + gl_GlobalInvocationID.x;
}

// The floor under `log2`, relative to mid-grey: 2^-20 is ~6 densities below
// it, far under any paper's toe. It is a numerical guard, not a signal -- a
// pixel that lands on it gets a bounded gain from the curve like any other.
const float kMaskLog2Floor = -20.0;

vec3 mask_image_exposure(uint i, uint nl) {
    const float c0 = mask_cmy[3u * i];
    const float c1 = mask_cmy[3u * i + 1u];
    const float c2 = mask_cmy[3u * i + 2u];
    float a0 = 0.0, a1 = 0.0, a2 = 0.0;
    for (uint l = 0u; l < nl; ++l) {
        const float d = c0 * mask_chd[3u * l] + c1 * mask_chd[3u * l + 1u] + c2 * mask_chd[3u * l + 2u]
                      + mask_base[l];
        const float t = exp2(-d * 3.321928094887362);
        a0 += t * mask_ixs[3u * l];
        a1 += t * mask_ixs[3u * l + 1u];
        a2 += t * mask_ixs[3u * l + 2u];
    }
    return vec3(a0 * mp[0], a1 * mp[1], a2 * mp[2]);
}

float mask_x(vec3 a) {
    float s = 0.0;
    for (int c = 0; c < 3; ++c) {
        const float r = max(a[c] * mp[3 + c], 0.0);
        s += max(log2(r), kMaskLog2Floor);   // log2(0) = -inf, then the floor
    }
    return s * (1.0 / 3.0);
}

// RFC-023's m = 2 branch, `D * H / sqrt(H^2 + D^2)`: slope 1 at the knee,
// asymptote H.
float mask_g(float d, float h) { return d * h * inversesqrt(h * h + d * d); }

// delta = f(b) - b, bounded. Exactly 0.0 inside the core and on a disabled
// branch, so the gain there is exp2(0) = 1.
float mask_delta(float b) {
    float delta = 0.0;
    if (mp[11] != 0.0 && b < mp[9]) {
        const float d = mp[9] - b;
        delta = d - mask_g(d, mp[10]);
    } else if (mp[14] != 0.0 && b > mp[12]) {
        const float d = b - mp[12];
        delta = mask_g(d, mp[13]) - d;
    }
    // The same smooth-min bounds the gain itself (RFC-023 §15's fix for an
    // unbounded shadow gain): |delta| < limit for any base.
    const float lim = mp[15];
    return delta * lim * inversesqrt(lim * lim + delta * delta);
}

#endif  // SPK_MASK_GLSL
