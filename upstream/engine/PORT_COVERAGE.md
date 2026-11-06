# Engine coverage on Linux / Windows (Vulkan) vs macOS (Metal)

The owner's question: *does the engine cover everything in the API spec over
C++ and Vulkan on the Windows branch?* This table answers it entry point by
entry point, field group by field group, kernel by kernel. It is kept current
by whoever changes a row (`engine/PORT_NOTES.md` is the running log).

Two columns of status:

- **Windows_Port (28a9d7c)** -- the state the clean textual merge of
  `origin/Windows_Port` onto main 1.3.1 left (before any Linux work).
- **Now** -- this branch, as last updated (date in the heading of §5).

Status words:

| word | means |
|---|---|
| **tested** | implemented, and a test on this branch exercises it on lavapipe (named) |
| **untested** | implemented, compiles and links, no test exercises it here |
| **refused** | the engine rejects the request by name (`SPK_ERR_USER`, message names the feature); capabilities list it under `backend.unsupported_features` |
| **missing** | absent; a call would fail or the feature is silently not there |

"Tested on lavapipe" is a CPU Vulkan device (Mesa llvmpipe, unfused `fma`).
Nothing here was run on a real GPU or on real Windows unless a row says so;
the Windows_Port branch's own acceptance (RTX 5070 Ti, Windows, MinGW GCC 15.2)
is recorded in `WINDOWS_FEATURE_COVERAGE.md` and predates main's 36 later
commits.

---

## 1. C ABI (`include/spektrafilm/spk_engine.h`)

| entry point | Windows_Port (28a9d7c) | Now | notes |
|---|---|---|---|
| `spk_build_info` | untested | tested (`spk_windows_c_abi`, host `hello`) | reports `math=probed` off Apple |
| `spk_last_error` | tested | tested | |
| `spk_engine_create` / `_destroy` | tested (Windows) | tested (`spk_vulkan_smoke`) | |
| `spk_capabilities` | tested (Windows) | tested (`spk_windows_feature_boundaries`) | `render_core: native-vulkan`; lists `unsupported_features` |
| `spk_params_schema` | tested | tested (`spk_core_schema`) | portable core |
| `spk_warm_up` | untested | untested | the host does not call it |
| `spk_open` | tested (Windows) | tested (`spk_vulkan_smoke`, `spk_windows_c_abi`) | |
| `spk_open_device` | refused | refused | device-buffer input is Metal-only; pass `null` |
| `spk_set_params` / `spk_get_params` | tested (Windows) | tested (`spk_windows_feature_boundaries`) | unported deltas rejected before mutation |
| `spk_solve` | untested | tested (host smoke `solve`) | portable (setup only) |
| `spk_render` (live/preview/full) | tested (Windows) | tested (`spk_windows_c_abi`) | |
| `spk_reprint` | tested (Windows) | tested (host smoke: `reprinted: true` after a print-side delta) | |
| `spk_contrast_mask_field` | refused | refused | RFC-024, see §2 |
| `spk_scene_latitude` (analysis) | tested (Windows) | tested (host smoke) | the *analysis* runs; *applying* the mapping is refused |
| `spk_overscan_geometry` | missing (link error) | tested: returns `{"valid":false}` | the merge left the symbol undefined; stubbed so the engine links |
| `spk_print_lut_catalog` / `spk_print_lut_table` | tested (Windows) | tested (host smoke `export_cube`) | portable core |
| `spk_preview_stock_lut` | tested (Windows) | tested (host smoke) | `spk_lut3d_trilinear` |
| `spk_export_di` | tested (Windows) | tested (host smoke `export_di`) | normalised-density tap |
| `spk_render_digital_intermediate` | refused | refused | Cineon DI (`spk_di_encode`) unported |
| `spk_output_transform` | untested | tested (every host `rgba8`/display render and export; a P3 export converted by lcms lands within MAE 0.6 % of the host's sRGB export) | portable core |
| `spk_memory_report` | untested | tested (host smoke) | |
| `spk_cancel` / `spk_progress` | untested | tested (host smoke: a full render cancelled mid-flight returns `SPK_ERR_CANCELLED` and the session renders again) | |
| `spk_result_free` / `spk_session_release` / `spk_string_free` | tested (Windows) | tested | Vulkan result owns a CPU copy |

## 2. API-SPEC wire fields

| section | fields | Windows_Port | Now |
|---|---|---|---|
| §1–§10 core render, tiers, reprint, live-mutable set | every field in `params_schema` predating RFC-024 | implemented; whole-image Metal parity unverified | same |
| §11 contrast mask (RFC-024) | `contrast_mask_{active,highlights,shadows,core,scale,scheme}` | **refused** when `active` | implemented (Deyva fork, r34 — see §6) |
| §12 Scene Latitude (RFC-023) | `scene_latitude_{active,norm,shadow_knee,highlight_knee,shadow_room,highlight_room,max_lift,rolloff}` | **refused** when `active` (analysis call works) | refused |
| §13 overscan / film edge (RFC-031/032) | `overscan_{active,mode,format,gate,holes,carrier,edge_text,frame_number,frame_seed,camera_seed,f_number,fog,leaks,pair}` | **refused** when `active` | refused |
| §13 date back | `date_imprint_{active,text,text_b,data_text,style,corner,placement,size,ev,inset_x,inset_y}` | **refused** when `active` | refused |
| §14 `filter_shift_scale` | `filter_shift_scale` | implemented (portable `printing.cpp`) | implemented, untested here |
| §15 `camera_filter` (2026-10-07) | `camera_filter` | — | implemented (portable `hanatos.cpp`, `params.cpp`; table in `camera_filters.hpp`), untested here; `desktop/mock-host/schema-fields.json` does not list it yet |
| `io.digital_intermediate` (Cineon DI file) | | refused | refused |
| anti-halation switch, highlight boost (9fa3b0d) | `halation`/`boost` fields | implemented via `spk_boost` | implemented, untested here |

## 3. Kernels: Metal vs Vulkan

| Metal kernel (`src/shaders/*.metal`) | Vulkan (`src/shaders/vulkan/*.comp`) | Windows_Port | Now |
|---|---|---|---|
| `spk_take_rgb`, `spk_affine3`, `spk_mul`, `spk_glare_add`, `spk_reduce_max`, `spk_to_rgba16`, `spk_stride_sample` | same names | tested (Windows) | tested (`spk_vulkan_pointwise`, `_transfer`) |
| `spk_zoom_bilinear_mirror` | same | tested | tested (`spk_vulkan_resample`) |
| `spk_math_probe` | same | tested | tested; lavapipe reports *unfused fma*, accepted only on a CPU device, and selects the Dekker path |
| `spk_lut3d_trilinear`, `spk_di_normalise` | same | tested | tested (`spk_vulkan_lut_di`) |
| `spk_sep_fir_acc` | same | tested | tested (`spk_vulkan_smoke` FIR oracle) |
| `spk_iir_df_acc` | same | tested | tested on lavapipe at the original bound (exact Dekker product on an unfused device, §5) |
| `spk_lincomb3` | same | tested | tested |
| `spk_log10_guarded`, `spk_boost`, `spk_curves`, `spk_matmul3`, `spk_tc_b`, `spk_lut2d_cubic`, `spk_couplers_correction`, `spk_spectral_epilogue`, `spk_print_exposure`, `spk_cctf_decode`, `spk_cctf_encode_matrix`, `spk_bw_correct`, `spk_edr` | same | tested (Windows) | tested (`spk_vulkan_pointwise`, `spk_windows_c_abi`) |
| `spk_geometry_resample_df` | same | tested | tested on lavapipe at the original bound (§5) |
| `spk_cam16ucs_compress` | same | tested | tested |
| `spk_grain_layers`, `spk_grain_simple`, `spk_lognormal_field`, `spk_grain_layer_one` | same (+ `spk_rng_probe`) | tested | tested (`spk_vulkan_grain`) |
| `spk_di_encode` (Cineon DI) | **missing** | refused | refused |
| `spk_mask_reduce`, `spk_mask_epilogue` (contrast mask) | **missing** | refused | implemented in the Deyva fork (§6); `src/shaders/vulkan/spk_mask*.comp` |
| `spk_scene_latitude` | **missing** | refused | refused |
| `spk_overscan_canvas`, `spk_overscan_add_mask`, `spk_overscan_film_present`, `spk_overscan_light` | **missing** | refused | refused |

Host-side (C++) dependencies of the unported features: `contrast_mask.cpp`
uses GCD (`dispatch_apply`); `overscan.cpp` uses CoreText/CoreGraphics for the
edge print and date back, and vImage for a convolution.

## 4. What main added after 61bfc49 that Vulkan lacks

36 commits; the engine ones, and their state off Apple:

| commit(s) | what | off Apple |
|---|---|---|
| 9fa3b0d | anti-halation switch, highlight boost | portable (existing kernels) |
| 2108544, 4a9ea54, 122c5fb, 4767b48, a2f7acb, abc55d5, 8179327, 9cc53c2, 3fe47b5, 079ec29, 25fe66f, b97b7fa, e985110, 75d8eb5, da9ae19 | film edge / overscan: frame number, panoramic formats, carrier, half-frame pair, perforation softness, Fujifilm/Kodak edge print | refused (overscan) |
| abc55d5, 079ec29 | a pair's second Scene Placement | refused (scene latitude) |
| 3ec73b2 | date back: date + shooting data, date on 120 | refused (date imprint) |
| f8223db | Enlarger filters move the print | portable (`printing.cpp`) |

The merge is textual; semantic reconciliation of `pipeline.cpp`/`engine.cpp`
between the two lines is verified only by the CTests named above.

## 5. Current state (2026-10-06)

**The five refused features stay refused, by the owner's decision**
(2026-10-06); `engine/PORT_PLAN.md` is the handoff for porting them.
Everything else in the API spec runs on Vulkan.

Verified on lavapipe (Mesa 25.2, llvmpipe LLVM 20, a CPU device):

- `ctest`: **14/14** at the original tolerances (IIR max abs error 1.49e-8 --
  the figure the Windows port recorded on an RTX 5070 Ti -- geometry 5.0e-7,
  resample 2.8e-7).
- `engine/tests/host_smoke.py` against `spektralab-host` on Linux: every
  host method, synthetic PNG and 16-bit TIFF, non-ASCII paths, a 12 MP NEF and
  a 21 MP CR2.
- The same smoke against `spektralab-host.exe` under wine 9 + Xvfb
  (winevulkan forwarding to lavapipe), including the NEF and a non-ASCII
  install folder.

Changes to the Vulkan backend made by this port (all behaviour-identical on
a GPU that already worked):

| change | why | where |
|---|---|---|
| dispatches past `maxComputeWorkGroupCount[0]` fold into 2D; shaders take `spk_global_index()` | 65535 groups x 256 = 16.7 M threads: every frame over 16.7 MP failed on lavapipe, and on Intel (same limit) | `src/gpu/vulkan_gpu.cpp` `dispatch`, every `src/shaders/vulkan/*.comp` |
| on a CPU device only, a start-up probe lifts `maxStorageBufferRange` (128 MiB on lavapipe) after proving larger SSBOs read and write correctly | nothing over ~11 MP rendered on the CPU fallback | `vulkan_gpu.cpp` `probe_cpu_storage_range`; off with `SPEKTRAFILM_VULKAN_STRICT_LIMITS=1` |
| exact Veltkamp/Dekker `two_prod` when `fma` is unfused (specialization constant 0) | lavapipe's `fma` is mul+add: the IIR blur lost its low word and erred by 0.4 % | `spk_iir_df_acc.comp`, `spk_geometry_resample_df.comp` |
| resources and SPIR-V opened through UTF-8 wide paths on Windows | an install folder under a non-ASCII user name would not start | `src/core/file_path.hpp`, four `ifstream` sites |

Not verified: any real GPU on Linux, real Windows (only wine), Intel/AMD
drivers, whole-image parity against Metal (needs a Mac; see PORT_PLAN §0),
cameras beyond one NEF and one CR2 (raw.pixls.us is blocked by this
environment's proxy).

---

## 6. The Deyva (Android) fork, r34: contrast mask ported

RFC-024's contrast mask is no longer refused on Vulkan. The port followed
`PORT_PLAN.md`'s own recipe for it:

| piece | where | note |
|---|---|---|
| host half | `src/pipeline/contrast_mask.cpp` | now compiled on both branches; its one Apple dependency (GCD's `dispatch_apply_f`) is behind `#ifdef __APPLE__`, with `src/pipeline/parallel_for.hpp` (`std::thread` + atomic line counter) otherwise. Same contract as the comment above `for_each_line`: one thread per line, inputs read-only, so the grid is bit-identical to the serial loop. |
| analysis kernel | `src/shaders/vulkan/spk_mask_reduce.comp` | translation of `contrast_mask.metal:80`; 7 bindings, `spk_global_index()` |
| application kernel | `src/shaders/vulkan/spk_mask_epilogue.comp` | translation of `contrast_mask.metal:109`, i.e. `spk_spectral_epilogue` plus the bounded gain on the image exposure before the pre-flash offset; writes log10 density like the unmasked node |
| shared helpers | `src/shaders/vulkan/spk_mask.glsl` | `mask_image_exposure`, `mask_x`, `mask_g`, `mask_delta` and the 16-float parameter block, verbatim from the Metal |
| refusal | `src/pipeline/windows_unported.cpp` | deleted; `spk_mask_*` no longer appear in `backend.unsupported_features` |

Verified here: both kernels compile with glslang 16.6 (`-V --target-env
vulkan1.0`); the SPIR-V declares bindings 0–6 / 0–7, set 0, one push constant
and `local_size_x = 256`, matching the C++ argument lists and `kWorkgroup`.
The host calibration was checked numerically against RFC-024's closed form
(`room_for`): an amount of N stops moves a base 4 stops past the knee by
exactly N (N = 0.5, 1, 2, 3) and leaves everything inside `core` untouched.
That check was correct and the effect was still invisible — see §7.

**Not verified:** the masked render on a real Adreno/vendor driver. This
backend has no Metal reference to diff against off Apple, and the machine that
built these binaries has no C++20 toolchain able to link the host (`gcc 8.1`'s
libstdc++ has no `char8_t`), so the first on-device run is the acceptance test.

---

## 7. The fork's r35: why the mask ran and did nothing

r34 put the mask on the wire and it ran: the device reports
`unsupported_features: ["digital_intermediate", "scene_latitude_mapping"]`,
every reprint succeeds, and the panel's numbers reach `spk_set_params`. The
picture did not change. The kernel was not the problem; the **number the
kernel was asked to compute** was.

RFC-024 reads each branch's amount at `kReach = 4` stops past its knee, i.e.
five stops from mid-grey, and fixes the curve's asymptote by inverting the
gain bound there. On the paper this app prints on there is no density left at
that point. Measured from `kodak_portra_endura.json` (`density_curves`,
green channel, mid-grey taken as D = 0.745 at 18 % reflectance):

| point | density | position, stops from mid-grey |
|---|---|---|
| paper white | 0.05 | −1.83 |
| light grey | 0.20 | −1.08 |
| mid-grey | 0.745 | 0 |
| near black | 1.80 | +1.91 |
| D-max | 2.07 | +2.4 |

The print is ±2 stops wide, and the mask's untouched core is ±1, so only about
0.8 stop at each end can move at all. `kReach = 4` puts the calibration point
two stops past paper white. There, an amount of 1.5 buys the curve a room
`H = 3.1`, which makes `delta` cubic near the knee:

| base | plain D | RFC: Δ stops | D | fork: Δ stops | D |
|---|---|---|---|---|---|
| −1.30 | 0.141 | +0.001 | 0.142 | +0.431 | 0.273 |
| −1.83 (white) | 0.050 | +0.028 | 0.052 | +0.958 | 0.271 |
| −2.20 | 0.021 | +0.081 | 0.026 | +1.152 | 0.210 |
| −3.00 | 0.002 | +0.319 | 0.005 | +1.342 | 0.071 |

At paper white an amount of 1.5 moved the print by 0.028 stop — 0.002 density,
two thousandths, which is a sixteenth of one 8-bit level. Even the maximum
amount of 3 only reached +0.38 stop / D 0.11. The mask was mathematically
incapable of moving an image, at any setting, on this paper. (The upstream
product hides the same section behind `FeatureFlags.toneMask = false`, "there
is still some issue behind that" — 2026-09-29. This is plausibly it.)

**The change.** `kReach = 1`, and the branch is calibrated directly:
`delta = amount/kReach * mask_g(b - knee, kReach)`. Slope 1 at the knee, and
the branch reads exactly `amount` one stop out and holds there. Slots 10/11
and 13/14 of the parameter block now carry `reach` and `amount/reach` (the
amount's own 0 is the branch's off switch), the trailing smooth bound is gone
because the curve can no longer exceed `amount`, and `room_for` is gone with
it. Both the Metal reference and the GLSL say the same thing now.

Verified with `work/mask/verify_mask.py`, which re-derives the grid, the
truncated-window Gaussian, the bilinear sample and the paper curve, then
prints the two tables above and renders the same photo through both
calibrations (`work/mask/mask_calibration.jpg`). Formula checks: `delta` is 0
inside the core, exactly `amount/√2` at the reach, within `±amount` everywhere,
and has the right sign on each side. The reduce kernel's SPIR-V is unchanged
(it never calls `mask_delta`), which is the expected fingerprint of the edit.

**Still not verified:** the pixels on the device — this machine cannot link the
host, so §6's caveat stands. What changed is that the effect is now large
enough that "no visible change" would mean the node is not running at all,
rather than running and computing nothing.
