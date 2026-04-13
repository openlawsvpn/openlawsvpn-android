cmake_minimum_required(VERSION 3.22)
project("openlawsvpn_jni")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Locate the openlawsvpn submodule ──────────────────────────────────────────
# The submodule lives at the repo root: openlawsvpn/ (openlawsvpn/openlawsvpn)
set(OPENLAWSVPN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../openlawsvpn")
set(OPENVPN3_CORE_DIR "${OPENLAWSVPN_DIR}/openvpn3-core")
set(LINUX_SRC_DIR "${OPENLAWSVPN_DIR}/linux/src")
set(LINUX_INC_DIR "${OPENLAWSVPN_DIR}/linux/include")

if(NOT EXISTS "${OPENLAWSVPN_DIR}/linux/src/libopenlawsvpn.cpp")
    message(FATAL_ERROR
        "openlawsvpn submodule not found at ${OPENLAWSVPN_DIR}\n"
        "Run: git submodule update --init --recursive")
endif()

# ── Find OpenSSL and LZ4 from the NDK sysroot / prebuilt packages ─────────────
# These must be provided as prebuilt .a files for Android (static linking).
# See docs/android-build.md for the recommended prebuilt setup.
find_library(log-lib log)
find_library(android-lib android)

# ── JNI bridge + shared libopenlawsvpn sources ────────────────────────────────
add_library(openlawsvpn_jni SHARED
    jni_bridge.cpp
    # Shared C++ core — same sources as Linux build, no D-Bus
    ${LINUX_SRC_DIR}/libopenlawsvpn.cpp
    ${LINUX_SRC_DIR}/saml_capture.cpp
    ${LINUX_SRC_DIR}/libopenlawsvpn_ffi.cpp
    ${OPENVPN3_CORE_DIR}/openvpn/crypto/data_epoch.cpp
)

target_compile_definitions(openlawsvpn_jni PRIVATE
    ASIO_STANDALONE
    USE_ASIO
    HAVE_LZ4
    USE_OPENSSL
    USE_TUN_BUILDER
    OPENVPN_CORE_API_VISIBILITY_HIDDEN
    # Android-specific: disables Linux tun paths in libopenlawsvpn.cpp
    __ANDROID__
)

target_include_directories(openlawsvpn_jni PRIVATE
    ${LINUX_INC_DIR}
    ${OPENVPN3_CORE_DIR}
)

# TODO: Add prebuilt OpenSSL and LZ4 static libs for Android NDK.
# Until then, the library will fail to link. See docs/android-build.md.
target_link_libraries(openlawsvpn_jni
    ${log-lib}
    ${android-lib}
    # OpenSSL (prebuilt .a — add path once available):
    # ssl crypto
    # LZ4 (prebuilt .a — add path once available):
    # lz4
)
