cmake_minimum_required(VERSION 3.22)
project("openlawsvpn_jni")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(ExternalProject)
include(FetchContent)

# ── Locate the openlawsvpn submodule ──────────────────────────────────────────
set(OPENLAWSVPN_DIR    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../openlawsvpn")
set(OPENVPN3_CORE_DIR  "${OPENLAWSVPN_DIR}/openvpn3-core")
set(LINUX_SRC_DIR      "${OPENLAWSVPN_DIR}/linux/src")
set(LINUX_INC_DIR      "${OPENLAWSVPN_DIR}/linux/include")

if(NOT EXISTS "${OPENLAWSVPN_DIR}/linux/src/libopenlawsvpn.cpp")
    message(FATAL_ERROR
        "openlawsvpn submodule not found at ${OPENLAWSVPN_DIR}\n"
        "Run: git submodule update --init --recursive")
endif()

# ── NDK toolchain helpers ─────────────────────────────────────────────────────
# ANDROID_ABI and ANDROID_NDK are set by the AGP CMake integration.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OPENSSL_ARCH "android-arm64")
    set(NDK_TRIPLE   "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(OPENSSL_ARCH "android-x86_64")
    set(NDK_TRIPLE   "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

set(NDK_TOOLCHAIN "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin")
set(NDK_CC  "${NDK_TOOLCHAIN}/${NDK_TRIPLE}${ANDROID_PLATFORM_LEVEL}-clang")
set(NDK_CXX "${NDK_TOOLCHAIN}/${NDK_TRIPLE}${ANDROID_PLATFORM_LEVEL}-clang++")
set(NDK_AR  "${NDK_TOOLCHAIN}/llvm-ar")

# Staging directory — per-ABI, inside the CMake build tree (gitignored).
set(DEPS_INSTALL_DIR "${CMAKE_BINARY_DIR}/deps/${ANDROID_ABI}")

# ── ASIO (header-only) ────────────────────────────────────────────────────────
FetchContent_Declare(
    asio
    GIT_REPOSITORY https://github.com/chriskohlhoff/asio.git
    GIT_TAG        asio-1-24-0
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(asio)
set(ASIO_INCLUDE_DIR "${asio_SOURCE_DIR}/asio/include")

# ── OpenSSL 3.3.2 ─────────────────────────────────────────────────────────────
set(OPENSSL_VER     "3.3.2")
set(OPENSSL_SHA256  "2e8a40b01979afe8be0bbfb3de5dc1c6709fedb46d6c89c10da114ab5fc3d281")
set(OPENSSL_INSTALL "${DEPS_INSTALL_DIR}/openssl")

ExternalProject_Add(openssl_build
    URL            "https://www.openssl.org/source/openssl-${OPENSSL_VER}.tar.gz"
    URL_HASH       SHA256=${OPENSSL_SHA256}
    DOWNLOAD_DIR   "${CMAKE_BINARY_DIR}/download"
    SOURCE_DIR     "${CMAKE_BINARY_DIR}/openssl-src"
    BINARY_DIR     "${CMAKE_BINARY_DIR}/openssl-build-${ANDROID_ABI}"
    INSTALL_DIR    "${OPENSSL_INSTALL}"
    CONFIGURE_COMMAND
        ${CMAKE_COMMAND} -E env
            ANDROID_NDK_ROOT=${ANDROID_NDK}
            PATH=${NDK_TOOLCHAIN}:$ENV{PATH}
        "${CMAKE_BINARY_DIR}/openssl-src/Configure"
            ${OPENSSL_ARCH}
            --prefix=${OPENSSL_INSTALL}
            no-shared no-tests no-apps no-docs
    BUILD_COMMAND
        ${CMAKE_COMMAND} -E env
            PATH=${NDK_TOOLCHAIN}:$ENV{PATH}
        make -j4 build_sw
    INSTALL_COMMAND
        ${CMAKE_COMMAND} -E env
            PATH=${NDK_TOOLCHAIN}:$ENV{PATH}
        make install_sw
    BUILD_BYPRODUCTS
        "${OPENSSL_INSTALL}/lib/libssl.a"
        "${OPENSSL_INSTALL}/lib/libcrypto.a"
)

add_library(ssl    STATIC IMPORTED)
add_library(crypto STATIC IMPORTED)
add_dependencies(ssl    openssl_build)
add_dependencies(crypto openssl_build)
set_target_properties(ssl    PROPERTIES IMPORTED_LOCATION "${OPENSSL_INSTALL}/lib/libssl.a")
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION "${OPENSSL_INSTALL}/lib/libcrypto.a")

# ── LZ4 1.8.3 ────────────────────────────────────────────────────────────────
set(LZ4_VER     "1.8.3")
set(LZ4_SHA256  "33af5936ac06536805f9745e0b6d61da606a1f8b4cc5c04dd3cbaca3b9b4fc43")
set(LZ4_INSTALL "${DEPS_INSTALL_DIR}/lz4")

ExternalProject_Add(lz4_build
    URL            "https://github.com/lz4/lz4/archive/refs/tags/v${LZ4_VER}.tar.gz"
    URL_HASH       SHA256=${LZ4_SHA256}
    DOWNLOAD_DIR   "${CMAKE_BINARY_DIR}/download"
    SOURCE_DIR     "${CMAKE_BINARY_DIR}/lz4-src"
    CONFIGURE_COMMAND ""
    BUILD_COMMAND
        make -C "${CMAKE_BINARY_DIR}/lz4-src/lib"
            CC=${NDK_CC}
            AR=${NDK_AR}
            RANLIB=${NDK_TOOLCHAIN}/llvm-ranlib
            liblz4.a -j4
    INSTALL_COMMAND
        ${CMAKE_COMMAND} -E make_directory "${LZ4_INSTALL}/include" "${LZ4_INSTALL}/lib"
        COMMAND ${CMAKE_COMMAND} -E copy
            "${CMAKE_BINARY_DIR}/lz4-src/lib/liblz4.a" "${LZ4_INSTALL}/lib/"
        COMMAND ${CMAKE_COMMAND} -E copy
            "${CMAKE_BINARY_DIR}/lz4-src/lib/lz4.h"
            "${CMAKE_BINARY_DIR}/lz4-src/lib/lz4frame.h"
            "${CMAKE_BINARY_DIR}/lz4-src/lib/lz4hc.h"
            "${LZ4_INSTALL}/include/"
    BUILD_BYPRODUCTS
        "${LZ4_INSTALL}/lib/liblz4.a"
)

add_library(lz4 STATIC IMPORTED)
add_dependencies(lz4 lz4_build)
set_target_properties(lz4 PROPERTIES IMPORTED_LOCATION "${LZ4_INSTALL}/lib/liblz4.a")

# ── JNI bridge + shared libopenlawsvpn sources ────────────────────────────────
find_library(log-lib     log)
find_library(android-lib android)

add_library(openlawsvpn_jni SHARED
    jni_bridge.cpp
    ${LINUX_SRC_DIR}/libopenlawsvpn.cpp
    ${LINUX_SRC_DIR}/saml_capture.cpp
    ${LINUX_SRC_DIR}/libopenlawsvpn_ffi.cpp
    ${OPENVPN3_CORE_DIR}/openvpn/crypto/data_epoch.cpp
)

add_dependencies(openlawsvpn_jni openssl_build lz4_build)

# openvpn3-core uses bitwise OR between different enum types (AlgFlags | Mode),
# which is deprecated in C++20. We cannot modify the submodule, so suppress it.
target_compile_options(openlawsvpn_jni PRIVATE
    -Wno-deprecated-enum-enum-conversion
)

target_compile_definitions(openlawsvpn_jni PRIVATE
    ASIO_STANDALONE
    USE_ASIO
    HAVE_LZ4
    USE_OPENSSL
    # USE_TUN_BUILDER is defined by ovpncli.cpp itself; defining it here causes a
    # macro-redefined warning because the command-line value (1) differs from the
    # empty definition in the source file.
    OPENVPN_CORE_API_VISIBILITY_HIDDEN
    __ANDROID__
)

target_include_directories(openlawsvpn_jni PRIVATE
    ${LINUX_INC_DIR}
    ${OPENVPN3_CORE_DIR}
    ${ASIO_INCLUDE_DIR}
    ${OPENSSL_INSTALL}/include
    ${LZ4_INSTALL}/include
)

target_link_libraries(openlawsvpn_jni
    ssl crypto lz4
    ${log-lib}
    ${android-lib}
)
