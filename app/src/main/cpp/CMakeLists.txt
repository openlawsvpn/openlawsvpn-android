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

# ── ASIO (header-only) ────────────────────────────────────────────────────────
# openvpn3-core expects standalone ASIO. It is not a git submodule — fetch it
# at configure time. Version must match openvpn3-core/deps/lib-versions.
include(FetchContent)
FetchContent_Declare(
    asio
    GIT_REPOSITORY https://github.com/chriskohlhoff/asio.git
    GIT_TAG        asio-1-24-0
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(asio)
# asio/include is the header root: contains asio.hpp and asio/
set(ASIO_INCLUDE_DIR "${asio_SOURCE_DIR}/asio/include")

# ── Prebuilt OpenSSL and LZ4 ──────────────────────────────────────────────────
# Build them once with: make deps  (runs scripts/build-deps-android.sh)
# Output lives in prebuilt/{openssl,lz4}/<ABI>/{include,lib}/
set(PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../prebuilt")
set(OPENSSL_DIR  "${PREBUILT_DIR}/openssl/${ANDROID_ABI}")
set(LZ4_DIR      "${PREBUILT_DIR}/lz4/${ANDROID_ABI}")

foreach(dep openssl lz4)
    set(dep_dir "${PREBUILT_DIR}/${dep}/${ANDROID_ABI}")
    if(NOT EXISTS "${dep_dir}/lib")
        message(FATAL_ERROR
            "Prebuilt ${dep} not found at ${dep_dir}\n"
            "Run:  make deps\n"
            "  or: ./scripts/build-deps-android.sh")
    endif()
endforeach()

find_library(log-lib     log)
find_library(android-lib android)

add_library(ssl    STATIC IMPORTED)
add_library(crypto STATIC IMPORTED)
add_library(lz4    STATIC IMPORTED)
set_target_properties(ssl    PROPERTIES IMPORTED_LOCATION "${OPENSSL_DIR}/lib/libssl.a")
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION "${OPENSSL_DIR}/lib/libcrypto.a")
set_target_properties(lz4    PROPERTIES IMPORTED_LOCATION "${LZ4_DIR}/lib/liblz4.a")

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
    ${ASIO_INCLUDE_DIR}
    ${OPENSSL_DIR}/include
    ${LZ4_DIR}/include
)

target_link_libraries(openlawsvpn_jni
    ssl crypto lz4
    ${log-lib}
    ${android-lib}
)
