// Explicit boundaries for effects added upstream after the first Windows port.
// Included only by Windows call sites; macOS retains its upstream behavior.
//
// [DEYVA r34] The contrast mask (RFC-024) has left this list: its host half
// compiles everywhere now (contrast_mask.cpp, with parallel_for.hpp under the
// one GCD call) and its two kernels exist as spk_mask_reduce/spk_mask_epilogue
// in src/shaders/vulkan. What remains refused is the Cineon DI (its
// spk_di_encode kernel is unported) and applying the Scene Latitude mapping
// (only its analysis runs; spk_scene_latitude itself works).
#pragma once
#include "params.hpp"

namespace spk {

inline const char* windows_unported_error(bool di, bool latitude) {
    if (di) return "digital intermediate is not implemented by the Windows Vulkan backend yet";
    if (latitude) return "scene latitude mapping is not implemented by the Windows Vulkan backend yet";
    return nullptr;
}

inline const char* windows_unported_error(const Params& p) {
    return windows_unported_error(p.io.digital_intermediate, p.camera.scene_latitude.active);
}

// Validate an already schema-checked delta before it mutates the session.
// A valid Windows session cannot already have any of these flags enabled.
// (overscan / date_imprint are implemented by overscan_port.cpp and pass;
// contrast_mask by contrast_mask.cpp and the two Vulkan kernels.)
inline const char* windows_unported_delta_error(const Json& delta) {
    return windows_unported_error(delta.at("digital_intermediate").as_bool(),
                                  delta.at("scene_latitude_active").as_bool());
}

}  // namespace spk
