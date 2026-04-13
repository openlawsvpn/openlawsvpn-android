#!/usr/bin/env bash
# Build OpenSSL and LZ4 static libraries for Android NDK (arm64-v8a + x86_64).
# Output goes to prebuilt/{openssl,lz4}/<ABI>/{include,lib}/
#
# Usage:
#   ./scripts/build-deps-android.sh
#
# Environment variables (all optional — script will try to auto-detect):
#   ANDROID_NDK   — path to NDK root (e.g. ~/Android/Sdk/ndk/30.0.14904198)
#   ANDROID_API   — minimum API level to target (default: 26)
#   OPENSSL_VER   — OpenSSL version to build (default: 3.3.2)
#   LZ4_VER       — LZ4 version to build (default: 1.8.3)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
PREBUILT_DIR="$REPO_DIR/prebuilt"
BUILD_DIR="$REPO_DIR/.deps-build"

ANDROID_API="${ANDROID_API:-26}"
OPENSSL_VER="${OPENSSL_VER:-3.3.2}"
LZ4_VER="${LZ4_VER:-1.8.3}"

ABIS=("arm64-v8a" "x86_64")

# ── Check Perl modules required by OpenSSL's Configure ───────────────────────
# On Fedora these are separate packages: perl-FindBin perl-IPC-Cmd perl-File-Compare
missing_perl=()
for mod in FindBin IPC::Cmd File::Compare File::Copy; do
    perl -M"$mod" -e1 2>/dev/null || missing_perl+=("$mod")
done
if [[ ${#missing_perl[@]} -gt 0 ]]; then
    echo "ERROR: Missing Perl modules: ${missing_perl[*]}"
    echo "  Fedora: sudo dnf install perl-FindBin perl-IPC-Cmd perl-File-Compare"
    echo "  Ubuntu: sudo apt-get install perl"
    exit 1
fi

# ── Locate NDK ────────────────────────────────────────────────────────────────

if [[ -z "${ANDROID_NDK:-}" ]]; then
    # Try Android Studio default locations
    for candidate in \
        "$HOME/Android/Sdk/ndk/"* \
        "$HOME/Library/Android/sdk/ndk/"* \
        "$ANDROID_HOME/ndk/"* \
        "$ANDROID_SDK_ROOT/ndk/"*; do
        if [[ -f "$candidate/source.properties" ]]; then
            ANDROID_NDK="$candidate"
        fi
    done
fi

if [[ -z "${ANDROID_NDK:-}" || ! -d "$ANDROID_NDK" ]]; then
    echo "ERROR: Android NDK not found. Set ANDROID_NDK=/path/to/ndk or install via Android Studio."
    exit 1
fi

NDK_TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
echo "NDK: $ANDROID_NDK"
echo "API: $ANDROID_API"

# ── Helpers ───────────────────────────────────────────────────────────────────

abi_to_openssl_arch() {
    case "$1" in
        arm64-v8a) echo "android-arm64" ;;
        x86_64)    echo "android-x86_64" ;;
        *) echo "ERROR: unknown ABI $1" >&2; exit 1 ;;
    esac
}

abi_to_triple() {
    case "$1" in
        arm64-v8a) echo "aarch64-linux-android" ;;
        x86_64)    echo "x86_64-linux-android" ;;
        *) echo "ERROR: unknown ABI $1" >&2; exit 1 ;;
    esac
}

# ── Build OpenSSL ─────────────────────────────────────────────────────────────

build_openssl() {
    local ABI="$1"
    local INSTALL_DIR="$PREBUILT_DIR/openssl/$ABI"

    if [[ -f "$INSTALL_DIR/lib/libssl.a" ]]; then
        echo "[openssl/$ABI] already built — skipping"
        return
    fi

    local ARCH
    ARCH="$(abi_to_openssl_arch "$ABI")"
    local TRIPLE
    TRIPLE="$(abi_to_triple "$ABI")"
    local SRC_DIR="$BUILD_DIR/openssl-$OPENSSL_VER"

    if [[ ! -d "$SRC_DIR" ]]; then
        echo "[openssl] downloading $OPENSSL_VER..."
        mkdir -p "$BUILD_DIR"
        curl -fsSL "https://www.openssl.org/source/openssl-$OPENSSL_VER.tar.gz" \
            -o "$BUILD_DIR/openssl-$OPENSSL_VER.tar.gz"
        tar -xzf "$BUILD_DIR/openssl-$OPENSSL_VER.tar.gz" -C "$BUILD_DIR"
    fi

    local BUILD_OUT="$BUILD_DIR/openssl-$OPENSSL_VER-$ABI"
    cp -a "$SRC_DIR" "$BUILD_OUT"
    pushd "$BUILD_OUT" > /dev/null

    echo "[openssl/$ABI] configuring..."
    export ANDROID_NDK_ROOT="$ANDROID_NDK"
    export PATH="$NDK_TOOLCHAIN:$PATH"

    ./Configure "$ARCH" \
        -D__ANDROID_API__="$ANDROID_API" \
        --prefix="$INSTALL_DIR" \
        no-shared no-tests no-apps no-docs

    echo "[openssl/$ABI] building..."
    make -j"$(nproc)" build_sw
    make install_sw

    popd > /dev/null
    rm -rf "$BUILD_OUT"
    echo "[openssl/$ABI] done → $INSTALL_DIR"
}

# ── Build LZ4 ─────────────────────────────────────────────────────────────────

build_lz4() {
    local ABI="$1"
    local INSTALL_DIR="$PREBUILT_DIR/lz4/$ABI"

    if [[ -f "$INSTALL_DIR/lib/liblz4.a" ]]; then
        echo "[lz4/$ABI] already built — skipping"
        return
    fi

    local TRIPLE
    TRIPLE="$(abi_to_triple "$ABI")"
    local SRC_DIR="$BUILD_DIR/lz4-$LZ4_VER"

    if [[ ! -d "$SRC_DIR" ]]; then
        echo "[lz4] downloading $LZ4_VER..."
        mkdir -p "$BUILD_DIR"
        curl -fsSL "https://github.com/lz4/lz4/archive/refs/tags/v$LZ4_VER.tar.gz" \
            -o "$BUILD_DIR/lz4-$LZ4_VER.tar.gz"
        tar -xzf "$BUILD_DIR/lz4-$LZ4_VER.tar.gz" -C "$BUILD_DIR"
    fi

    local BUILD_OUT="$BUILD_DIR/lz4-$LZ4_VER-$ABI"
    cp -a "$SRC_DIR" "$BUILD_OUT"

    echo "[lz4/$ABI] building..."
    make -C "$BUILD_OUT/lib" \
        CC="$NDK_TOOLCHAIN/${TRIPLE}${ANDROID_API}-clang" \
        AR="$NDK_TOOLCHAIN/llvm-ar" \
        RANLIB="$NDK_TOOLCHAIN/llvm-ranlib" \
        liblz4.a \
        -j"$(nproc)"

    mkdir -p "$INSTALL_DIR/include" "$INSTALL_DIR/lib"
    cp "$BUILD_OUT/lib/liblz4.a"   "$INSTALL_DIR/lib/"
    cp "$BUILD_OUT/lib/lz4.h"      "$INSTALL_DIR/include/"
    cp "$BUILD_OUT/lib/lz4frame.h" "$INSTALL_DIR/include/"
    cp "$BUILD_OUT/lib/lz4hc.h"    "$INSTALL_DIR/include/"

    rm -rf "$BUILD_OUT"
    echo "[lz4/$ABI] done → $INSTALL_DIR"
}

# ── Main ──────────────────────────────────────────────────────────────────────

for ABI in "${ABIS[@]}"; do
    build_openssl "$ABI"
    build_lz4 "$ABI"
done

echo ""
echo "All deps built. Prebuilt tree:"
find "$PREBUILT_DIR" -name "*.a" | sort
