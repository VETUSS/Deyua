# app/src/main/cpp/CMakeLists.txt
# 编译 JNI 桥接 libjni_bridge.so，链接预编译的 Spektrafilm 引擎 .so（位于 jniLibs/<abi>）。
cmake_minimum_required(VERSION 3.22.1)
project(spektra_jni_bridge)

# 上游引擎公共头（spk_engine.h）
set(UPSTREAM_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../upstream/engine/include)

# [v0.8.98] AGP 的 debug 变体传 CMAKE_BUILD_TYPE=Debug，即 -O0 且断言全开：
# 静态链进来的 LibRaw 因此慢 2~4 倍（证据：0.8.97 的 libjni_raw.so 动态符号表里有
# __assert2，而 0.8.93 的发布包没有 —— 旧包是 -DNDEBUG 的优化构建）。
# 这里补回优化；断言先留着，方便继续抓引擎侧问题。
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O2)
endif()

# JNI 桥接
add_library(jni_bridge SHARED jni_bridge.cpp)
target_include_directories(jni_bridge PRIVATE ${UPSTREAM_INCLUDE})
target_compile_features(jni_bridge PUBLIC cxx_std_20)

# 链接预编译引擎 .so
add_library(spektrafilm_engine SHARED IMPORTED)
set_target_properties(spektrafilm_engine PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/spektrafilm_engine.so)
target_link_libraries(jni_bridge PRIVATE spektrafilm_engine)

# ---------------------------------------------------------------------------
# RAW 解码 JNI（jni_raw.so）：复用引擎 spk_image_io 的 raw_decoder + 固定版 LibRaw 0.22.2
# 引擎核心 spektrafilm_engine.so 不含 raw_decoder，故在此独立编译。
# ---------------------------------------------------------------------------
set(UPSTREAM ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../upstream)
set(SPEKTRALAB_LIBRAW_SOURCE "${UPSTREAM}/build/deps/LibRaw-0.22.2" CACHE PATH "LibRaw 0.22.2 source" FORCE)
include("${UPSTREAM}/engine/cmake/LibRaw.cmake")

add_library(jni_raw SHARED
    jni_raw.cpp
    "${UPSTREAM}/engine/src/io/raw_decoder.cpp"
    "${UPSTREAM}/engine/src/io/raw_headroom.cpp")
target_include_directories(jni_raw PRIVATE "${UPSTREAM}/engine/src" ${UPSTREAM_INCLUDE})
target_compile_features(jni_raw PUBLIC cxx_std_20)
target_link_libraries(jni_raw PRIVATE spk_libraw spektrafilm_engine)

# [r40] Sony ARW 6.0（A7 V 的 Compressed / Compressed HQ）解码器 backport。
# 上游 LibRaw 0.23 尚未发布，补丁见 libraw-arw6.patch，全部改动都在
# SPEKTRA_ARW6_DECODER 宏内；关闭时与官方 0.22.2 逐位一致。
option(SPEKTRA_ARW6 "Backport Sony ARW 6.0 compressed decoder into LibRaw 0.22.2" ON)
if (SPEKTRA_ARW6)
    # 必须同时加在 LibRaw 上：sony_arw6.cpp 与 tiff.cpp 的 32766 分支都在
    # spk_libraw 里编译，只给 jni_raw 定义宏只会让诊断日志报 arw6=1，而真正
    # 的解码器仍是空翻译单元。
    target_compile_definitions(spk_libraw PRIVATE SPEKTRA_ARW6_DECODER=1)
    target_compile_definitions(jni_raw PRIVATE SPEKTRA_ARW6_DECODER=1)
endif()
