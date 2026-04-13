# openlawsvpn-android

Android client for [openlawsvpn](https://github.com/openlawsvpn/openlawsvpn) — native app (Kotlin + JNI/NDK).

## Build prerequisites

### 1. Initialize submodules

The C++ core and openvpn3-core are nested git submodules.

```sh
make init
# or manually:
git submodule update --init --recursive
```

### 2. Build native deps (OpenSSL + LZ4)

The NDK build requires static libraries for `arm64-v8a` and `x86_64`.
The script downloads sources and cross-compiles them using the NDK clang toolchain.

```sh
make deps
# or manually:
./scripts/build-deps-android.sh
```

The NDK is auto-detected from `~/Android/Sdk/ndk/*` or `$ANDROID_NDK`.
Output goes to `prebuilt/` (gitignored). Run once; re-run only when updating library versions.

### 3. Android Studio

Open the project root in Android Studio (AGP 9.x, NDK 30+).
The IDE will sync Gradle and invoke CMake automatically.

## Development

```sh
make init          # initialize / update all submodules (run once after cloning)
make deps          # cross-compile OpenSSL + LZ4 for arm64-v8a and x86_64 (run once)
make clean         # Gradle clean
make clean-deps-build  # remove downloaded source tarballs (keeps prebuilt/ output)
```

Full build, run, and device management are handled through Android Studio or
the Gradle wrapper (`./gradlew assembleDebug`).

## Architecture

- **VpnConnectionService** — runs Phase 1/2 via JNI, owns the SAML callback server
- **jni_bridge.cpp** — JNI wrapper; wires tun/protect/log callbacks from C++ threads to the service
- **openlawsvpn/** — git submodule; contains the shared C++ core (`libopenlawsvpn`) and openvpn3-core
- **SamlCallbackServer** — HTTP server on `127.0.0.1:35001` (AWS-hardcoded ACS endpoint)

See `CLAUDE.md` for full architecture notes and `openlawsvpn/linux/include/libopenlawsvpn.h`
for the C++ API surface.
