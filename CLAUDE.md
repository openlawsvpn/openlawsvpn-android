# Claude context for openlawsvpn-android

Android client for openlawsvpn — full native app with JNI/NDK.

## Repo structure

```
CLAUDE.md
app/
  src/main/
    cpp/
      CMakeLists.txt          — NDK build; compiles libopenlawsvpn sources from submodule
      jni_bridge.cpp          — JNI wrapper; registers tun/protect/log callbacks
    java/com/openlawsvpn/android/
      App.kt                  — Application subclass
      MainActivity.kt         — single activity, nav host
      VpnConnectionService.kt — VpnService: runs Phase 1/2, owns SamlCallbackServer
      SamlCallbackServer.kt   — HTTP server on 127.0.0.1:35001 (ACS endpoint)
      ProfileManager.kt       — .ovpn profile storage (app private dir)
      jni/LibOpenLawsVpn.kt   — JNI declarations
      model/                  — ConnectionState, VpnProfile
      ui/                     — ConnectionFragment, LogFragment, ConnectionViewModel
openlawsvpn/                  — git submodule (openlawsvpn/openlawsvpn)
  linux/src/libopenlawsvpn.cpp   — shared C++ core
  linux/src/saml_capture.cpp
  linux/include/libopenlawsvpn.h
  openvpn3-core/              — submodule of submodule
```

## Key architecture decisions

- **No copy-paste**: libopenlawsvpn C++ is shared via git submodule.
- **Callbacks**: libopenlawsvpn exposes `set_tun_establish_fn` / `set_socket_protect_fn`;
  jni_bridge.cpp wires these to VpnConnectionService.buildTun() and VpnService.protect().
- **Threading**: Phase 1 and Phase 2 block on Dispatchers.IO; all JNI callbacks are
  invoked from openvpn3-core internal threads (attached to JVM in the bridge).
- **SAML flow**: SamlCallbackServer listens on :35001. Chrome Custom Tab opens the
  SAML URL. AWS SSO SPA POSTs SAMLResponse to :35001. Validated on device (2026-04-13).

## Build prerequisites

1. Initialize submodules: `git submodule update --init --recursive`
2. Provide prebuilt OpenSSL + LZ4 static libs for Android NDK arm64-v8a / x86_64.
   Update CMakeLists.txt with the lib paths.
3. Open in Android Studio (AGP 8.13, NDK 26+).

## References

- Context: openlawsvpn/openlawsvpn-context (architecture, SAML flow details)
- Tasks: openlawsvpn/openlawsvpn-private tasks/06_mobile/overview.md
- PoC: android/poc/ in openlawsvpn/openlawsvpn
