# Changelog

All notable changes to openlawsvpn for Android are documented here.

---

## [0.1.1] — 2026-04-14

### Fixed
- Post-notifications permission added (required on Android 13+ for foreground service)
- AGP 9 migration: removed deprecated `kotlinOptions` DSL; use `kotlin { compilerOptions {} }`
- AGP 9 migration: removed conflicting `org.jetbrains.kotlin.android` plugin application
- OpenSSL build: removed redundant `-D__ANDROID_API__` flag (NDK 23+ defines it automatically)
- CMakeLists: suppressed `-Wdeprecated-enum-enum-conversion` from openvpn3-core headers
- submodule: fixed VLA (`-Wvla-cxx-extension`) in `saml_capture.cpp`

### Build
- NDK upgraded to 30.0.14904198
- AGP upgraded to 9.1.1
- Kotlin upgraded to 2.2.10
- Navigation SafeArgs plugin upgraded to 2.9.6

---

## [0.1.0] — 2026-04-13

### Added
- Initial Android client: Kotlin + JNI/NDK, VpnService, SAML via Chrome Custom Tabs
- AWS Client VPN CRV1/SAML two-phase connection flow
- Profile management (.ovpn files stored in app-private storage)
- VPN log viewer
- End-to-end VPN connection confirmed on physical device
