// parallel_for.hpp -- the portable half of `for_each_line` (RFC-024's comment
// in contrast_mask.cpp is the contract: each line is written by one thread and
// read-only otherwise, so any partition of the lines is bit-identical to the
// serial loop).
//
// macOS keeps GCD (`dispatch_apply_f`) because that is what the Apple half of
// this engine was measured with. Everywhere else -- Windows, Linux, Android --
// this is the same work over std::thread: a static worker count, an atomic
// counter handing out line indices, and the calling thread joining in so a
// single-core device still makes progress.
#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <thread>
#include <vector>

namespace spk {

template <class F>
void parallel_for_lines(uint32_t lines, F&& fn) {
    if (lines == 0) return;
    unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 2;
    const uint32_t workers = std::min<uint32_t>(lines, hw);
    if (workers <= 1) {
        for (uint32_t line = 0; line < lines; ++line) fn(line);
        return;
    }
    std::atomic<uint32_t> next{0};
    auto body = [&]() {
        for (;;) {
            const uint32_t line = next.fetch_add(1, std::memory_order_relaxed);
            if (line >= lines) return;
            fn(line);
        }
    };
    std::vector<std::thread> pool;
    pool.reserve(workers - 1);
    for (uint32_t w = 1; w < workers; ++w) pool.emplace_back(body);
    body();
    for (auto& t : pool) t.join();
}

}  // namespace spk
