# openlawsvpn for Android

[![CI](https://github.com/openlawsvpn/openlawsvpn-android/actions/workflows/ci.yml/badge.svg)](https://github.com/openlawsvpn/openlawsvpn-android/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

Android client for [openlawsvpn](https://github.com/openlawsvpn/openlawsvpn) — connect to AWS Client VPN endpoints with SAML/SSO (Okta, Azure AD, Google Workspace, any SAML 2.0 IdP).

## Features

- Connects to AWS Client VPN with SAML single sign-on — no passwords stored on device
- Opens your company IdP login in a secure Chrome Custom Tab
- Manage multiple VPN profiles — import, connect, delete per profile
- Per-profile live status: Connecting / Connected / Session expired
- Automatic detection of network loss and server-side session drops
- Real-time connection log viewer
- Persistent foreground notification with one-tap Disconnect
- VPN profiles encrypted at rest with AES-256-GCM (Android Keystore)

## Build prerequisites

### 1. Initialize submodules

```sh
make init
```

The C++ core and openvpn3-core are nested git submodules.

### 2. Android Studio

Open the project root in Android Studio (AGP 9.x, NDK 30+).

OpenSSL and LZ4 are built from source automatically by CMake on the first build — no manual pre-build step required.

## Development

```sh
make init              # initialize / update all submodules
make lint              # run lint checks
make test              # run unit tests
make release           # build signed release APK (requires pass store)
make bundle            # build signed release AAB for Play Store
make clean             # Gradle clean
make clean-deps-build  # remove downloaded source tarballs
```

## Architecture

- **VpnConnectionService** — VpnService subclass; runs Phase 1/2 via JNI, owns the SAML callback server
- **jni_bridge.cpp** — JNI wrapper; wires tun/protect/log callbacks from C++ threads to the service
- **ProfileManager** — stores `.ovpn` profiles encrypted at rest in app-private storage
- **SamlCallbackServer** — HTTP server on `127.0.0.1:35001` (AWS-hardcoded ACS endpoint)
- **openlawsvpn/** — git submodule; shared C++ core (`libopenlawsvpn`) and openvpn3-core

See `openlawsvpn/linux/include/libopenlawsvpn.h` for the C++ API surface.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

> **Archived:** This repo is a proof-of-concept (C++/JNI/NDK stack). The active Android client is [openlawsvpn-android-go](https://github.com/openlawsvpn/openlawsvpn-android-go) (pure Go, no NDK).
