// diag.hpp -- [DEYVA-DIAG v0.8.92] A shared, append-only diagnostic string that
// the render path writes into and the overscan JSON report emits.
//
// Why this exists: the 片边 (film-edge) picture comes out with the top-left
// quarter of the photo intact and everything past the frame's midpoint clamped
// to the last valid row/column. The canvas is provably correct (its rebate and
// perforations are drawn across the whole sheet, and the gate's probes match
// the CPU's mapping to the last digit), so the damage is already in the frame
// buffer the overscan node receives. The frame is produced by a chain of
// pointwise kernels, and nothing in the source says which of them stops at the
// halfway mark -- so instead of guessing, every stage measures its own output
// and the numbers ride out in the JSON the host already logs.
//
// The probes are luminance only: eight samples per image (five across the
// centre row, three down the centre column), printed at %.3g. Compact on
// purpose -- the line already carries the layout report and the log panel is
// not infinitely wide.
#pragma once
#include <cstdarg>
#include <cstdio>
#include <string>

#include "image.hpp"

namespace spk {

// One string for the whole render; cleared by the caller at the top of a frame.
inline std::string& diag_log() {
    static std::string s;
    return s;
}

inline void diag_reset() { diag_log().clear(); }

inline void diag_addf(const char* fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    diag_log() += buf;
}

// Defined in pipeline.cpp. `diag_probe_image` reads back through the device;
// `diag_probe_host` samples a tightly packed host plane (row-major, 3 floats
// per pixel) without a round trip.
void diag_probe_image(gpu::Gpu* g, const char* tag, const Image& img);
void diag_probe_host(const char* tag, const float* px, uint32_t w, uint32_t h, uint32_t c);

}  // namespace spk
