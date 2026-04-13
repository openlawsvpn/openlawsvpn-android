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

### 2. Prebuilt OpenSSL + LZ4 (Android NDK)

The NDK build requires static libraries for `arm64-v8a` and `x86_64`.
Cross-compile them with the NDK toolchain or use a prebuilt package, then
update the paths in `app/src/main/cpp/CMakeLists.txt`.

### 3. Android Studio

Open the project root in Android Studio (AGP 9.x, NDK 30+).
The IDE will sync Gradle and invoke CMake automatically.

## Development

```sh
make init          # initialize / update all submodules
make submodules    # same as init
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
