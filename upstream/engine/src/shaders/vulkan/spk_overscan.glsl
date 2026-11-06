// spk_overscan.glsl -- GLSL translation of the overscan helper functions in
// overscan.metal (RFC-032 §26-§27), transferred verbatim apart from the
// platform spelling (Metal -> GLSL). Keep the OsP index layout, the geometry,
// and the random streams intact: the host builds the parameter block from
// overscan.cpp's `enum OsP`, and uploads it to the `P` SSBO that every
// including .comp declares at binding 1.
#ifndef SPK_OVERSCAN_GLSL
#define SPK_OVERSCAN_GLSL

// Indices into the kernel parameter block; overscan.cpp (enum OsP) is the
// single source of these values. Mirrored here.
#define P_CW         0
#define P_CH         1
#define P_PX         2
#define P_FW         3
#define P_FH         4
#define P_A00        5
#define P_A01        6
#define P_A10        7
#define P_A11        8
#define P_CS         9
#define P_CT         10
#define P_CU         11
#define P_CV         12
#define P_VERTICAL   13
#define P_GS0        14
#define P_GT0        15
#define P_GW         16
#define P_GH         17
#define P_CORNER     18
#define P_PENUMBRA   19
#define P_WOB_A      20   // 4 sides x 3 harmonics
#define P_WOB_PH     32   // 4 sides x 3 harmonics
#define P_FILM_W     44
#define P_FOG_AMP    45
#define P_FOG_WIDTH  46
#define P_FOG_SEED   47
#define P_FOG_PERIOD 48
#define P_FOG_R      49
#define P_FOG_G      50
#define P_FOG_B      51
#define P_N_LEAKS    52
#define P_PERFORATED 83
#define P_PERF_PITCH 84
#define P_PERF_W     85
#define P_PERF_H     86
#define P_PERF_EDGE  87
#define P_PERF_R     88
#define P_PERF_PHASE 89
#define P_HOLE_C     90
#define P_HOLE_M     91
#define P_HOLE_Y     92
#define P_FLARE_AMP  93
#define P_FLARE_WIDTH 94
#define P_GATE_R     95  // 4 corner radii: (s-,t-), (s+,t-), (s+,t+), (s-,t+)
#define P_N_Q        99
#define P_Q          100 // up to 8 x (4 vertices s,t; round; sign)
#define P_ROUGH_AMP  180
#define P_ROUGH_PERIOD 181
#define P_ROUGH_SEED 182
#define P_HOLES_LIGHT 183
#define P_LIGHT_R    184
#define P_LIGHT_G    185
#define P_LIGHT_B    186
#define P_PERF_SEED  187
#define P_LIGHT_FALL 188
#define P_LIGHT_DIR  189
#define P_WALL_T     190
#define P_WALL_VS    191
#define P_WALL_VT    192
#define P_WALL_PAR   193
#define P_BASE_R     194
#define P_BASE_G     195
#define P_BASE_B     196
#define P_WALL_GLOW  197
#define P_WALL_SCATTER 198
#define P_CARRIER    199
#define P_LEAKS      200 // up to 12 x (s, side, amp, sigma_s, sigma_t)
#define P_PAIR_ADV   260
#define P_COUNT      261
#define kQStride     10u
#define kMaxQuads    8u

const float kPi = 3.141592653589793;

// The parameter block every overscan kernel reads, uploaded by the host from
// overscan.cpp's `overscan_params_block` (enum OsP). Declared here so the
// helpers below can read it; including .comp files must NOT redeclare it.
layout(std430, binding = 1) readonly buffer Parameters { float P[P_COUNT]; };

// 1D dispatch index -- vulkan_gpu.cpp folds past maxComputeWorkGroupCount[0].
layout(push_constant) uniform SpkDispatch { uint base; uint origin; uint read_bias; } spk_pc;
uint spk_global_index() {
    return spk_pc.base + gl_GlobalInvocationID.y * (gl_NumWorkGroups.x * gl_WorkGroupSize.x) + gl_GlobalInvocationID.x;
}

vec2 canvas_to_film(float x, float y) {
    const float u = (x + 0.5) * P[P_PX] - P[P_CU];
    const float v = (y + 0.5) * P[P_PX] - P[P_CV];
    return vec2(P[P_A00] * u + P[P_A01] * v + P[P_CS], P[P_A10] * u + P[P_A11] * v + P[P_CT]);
}

uint hash_u(uint x) {
    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u; x *= 0x846ca68bu; x ^= x >> 16u;
    return x;
}
float hash_f(int i, uint seed) { return float(hash_u(uint(i) * 0x9E3779B9u ^ seed)) * (1.0 / 4294967295.0); }
float value_noise(float x, uint seed) {
    const float i0 = floor(x), f = x - i0;
    const float w = f * f * (3.0 - 2.0 * f);
    return mix(hash_f(int(i0), seed), hash_f(int(i0) + 1, seed), w);
}

// Signed distance to a convex quad (either winding; a repeated vertex makes a
// triangle), negative inside.
float sd_quad(vec2 p, int qbase) {
    vec2 v0 = vec2(P[qbase+0], P[qbase+1]);
    vec2 v1 = vec2(P[qbase+2], P[qbase+3]);
    vec2 v2 = vec2(P[qbase+4], P[qbase+5]);
    vec2 v3 = vec2(P[qbase+6], P[qbase+7]);
    float d = dot(p - v0, p - v0);
    float sgn = 1.0;
    vec2 v[4] = vec2[4](v0, v1, v2, v3);
    for (uint ii = 0u, j = 3u; ii < 4u; j = ii, ++ii) {
        const vec2 e = v[j] - v[ii], w = p - v[ii];
        const float ee = dot(e, e);
        if (ee < 1e-12) continue;
        const vec2 b = w - e * clamp(dot(w, e) / ee, 0.0, 1.0);
        d = min(d, dot(b, b));
        const bool c0 = p.y >= v[ii].y, c1 = p.y < v[j].y, c2 = e.x * w.y > e.y * w.x;
        if ((c0 && c1 && c2) || (!c0 && !c1 && !c2)) sgn = -sgn;
    }
    return sgn * sqrt(d);
}

// The gate: asymmetric rounded rectangle with per-corner radii, camera wobble,
// additive/cut quads and machined roughness. Signed distance, negative inside.
float gate_sdf(vec2 st) {
    const float hw = 0.5 * P[P_GW], hh = 0.5 * P[P_GH];
    const float ds = st.x - (P[P_GS0] + hw), dt = st.y - (P[P_GT0] + hh);
    const float r = ds > 0.0 ? (dt > 0.0 ? P[P_GATE_R + 2u] : P[P_GATE_R + 1u])
                             : (dt > 0.0 ? P[P_GATE_R + 3u] : P[P_GATE_R + 0u]);
    const float qs = abs(ds) - (hw - r), qt = abs(dt) - (hh - r);
    float d = length(max(vec2(qs, qt), 0.0)) + min(max(qs, qt), 0.0) - r;
    uint side; float along, len;
    if (qs > qt) { side = ds > 0.0 ? 1u : 3u; along = dt + hh; len = 2.0 * hh; }
    else         { side = dt > 0.0 ? 2u : 0u; along = ds + hw; len = 2.0 * hw; }
    const float uu = clamp(along / len, 0.0, 1.0);
    float w = 0.0;
    for (uint k = 0u; k < 3u; ++k)
        w += P[P_WOB_A + side * 3u + k] * sin(float(k + 1u) * kPi * uu + P[P_WOB_PH + side * 3u + k]);
    d -= w * sin(kPi * uu);
    const uint nq = uint(P[P_N_Q]);
    for (uint k = 0u; k < nq; ++k) {
        const int qb = P_Q + int(kQStride) * int(k);
        float dq;
        if (abs(P[qb + 9]) > 1.5) {
            const vec2 rad = max(vec2(P[qb+2], P[qb+3]), vec2(1e-4));
            dq = (length((st - vec2(P[qb+0], P[qb+1])) / rad) - 1.0) * min(rad.x, rad.y);
        } else {
            dq = sd_quad(st, qb) - P[qb + 8];
        }
        d = P[qb + 9] > 0.0 ? min(d, dq) : max(d, -dq);
    }
    if (P[P_ROUGH_AMP] > 0.0) {
        const uint seed = uint(P[P_ROUGH_SEED]);
        const float per = max(P[P_ROUGH_PERIOD], 1e-3);
        d += P[P_ROUGH_AMP] * (value_noise(st.x / per, seed) + value_noise(st.y / per, seed ^ 0x5bd1e995u) - 1.0);
    }
    return d;
}

// Lit fraction of a disc of radius r cut by a straight edge at distance d.
float penumbra(float d, float r) {
    if (r <= 1e-6) return d < 0.0 ? 1.0 : 0.0;
    const float u = clamp(-d / r, -1.0, 1.0);
    return 0.5 + (u * sqrt(max(1.0 - u * u, 0.0)) + asin(u)) * (1.0 / kPi);
}

// Nearest perforation's signed distance (mm, negative inside) + the hole's hash.
float perf_sdf(vec2 st, inout uint hole) {
    const float W = P[P_FILM_W];
    const float pitch = P[P_PERF_PITCH], ph = P[P_PERF_PHASE];
    const float k = floor((st.x - ph - 0.5 * P[P_PERF_W]) / pitch + 0.5);
    const bool top = st.y < 0.5 * W;
    const uint seed = uint(P[P_PERF_SEED]);
    hole = hash_u(uint(int(k) * 2 + (top ? 0 : 1)) * 0x9E3779B9u ^ seed);
    const float j0 = hash_f(int(hole & 0xFFFFu), seed ^ 0x1u) - 0.5;
    const float j1 = hash_f(int(hole & 0xFFFFu), seed ^ 0x2u) - 0.5;
    const float j2 = hash_f(int(hole & 0xFFFFu), seed ^ 0x3u) - 0.5;
    const float j3 = hash_f(int(hole & 0xFFFFu), seed ^ 0x4u) - 0.5;
    const float j4 = hash_f(int(hole & 0xFFFFu), seed ^ 0x5u) - 0.5;
    const float wa = P[P_PERF_W] + 0.016 * j2, hc = P[P_PERF_H] + 0.016 * j3;
    const float r = clamp(P[P_PERF_R] + 0.08 * j4, 0.3, 0.6);
    const float sc = ph + k * pitch + 0.5 * P[P_PERF_W] + 0.020 * j0;
    const float tc = (top ? P[P_PERF_EDGE] + 0.5 * P[P_PERF_H] : W - P[P_PERF_EDGE] - 0.5 * P[P_PERF_H]) + 0.024 * j1;
    const vec2 q = abs(vec2(st.x - sc, st.y - tc)) - vec2(0.5 * wa - r, 0.5 * hc - r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    // the punched edge: each hole's own roughness, 1-4 microns
    const float rough = 0.001 + 0.003 * hash_f(int(hole & 0xFFFFu), seed ^ 0x6u);
    d += rough * (value_noise(st.x / 0.05, seed ^ 0x77u) + value_noise(st.y / 0.05, seed ^ 0x99u) - 1.0) * 2.0;
    // a burr or chip on about one hole in four
    if (hash_f(int(hole & 0xFFFFu), seed ^ 0x7u) < 0.25) {
        const float ang = 6.2831853 * hash_f(int(hole & 0xFFFFu), seed ^ 0x8u);
        const vec2 pb = vec2(sc, tc) + vec2(cos(ang) * 0.5 * wa, sin(ang) * 0.5 * hc);
        const float sig = 0.020 + 0.030 * hash_f(int(hole & 0xFFFFu), seed ^ 0x9u);
        const float amp = (hash_f(int(hole & 0xFFFFu), seed ^ 0xAu) < 0.5 ? -1.0 : 1.0) *
                          (0.006 + 0.010 * hash_f(int(hole & 0xFFFFu), seed ^ 0xBu));
        d += amp * exp(-dot(st - pb, st - pb) / (sig * sig));
    }
    return d;
}

const float kHoleEdgeSigma = 0.017;

// erf, Abramowitz and Stegun 7.1.26 (|error| < 1.5e-7).
float erf_as(float x) {
    const float t = 1.0 / (1.0 + 0.3275911 * abs(x));
    const float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x);
    return x < 0.0 ? -y : y;
}

// Where there is film: 1 on the strip, 0 in the perforations and past its edges.
float film_alpha(vec2 st) {
    const float W = P[P_FILM_W], px = P[P_PX];
    float a = P[P_CARRIER] > 1.5 ? 1.0 : clamp(min(st.y, W - st.y) / px + 0.5, 0.0, 1.0);
    if (P[P_PERFORATED] > 0.5) {
        uint hole;
        const float sigma = sqrt(kHoleEdgeSigma * kHoleEdgeSigma + 0.0961 * px * px);
        a *= 0.5 + 0.5 * erf_as(perf_sdf(st, hole) / (1.41421356 * sigma));
    }
    return a;
}

#endif
