// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.jni

/**
 * JNI declarations for libopenlawsvpn_jni.so.
 *
 * The native library is built from:
 *   app/src/main/cpp/jni_bridge.cpp
 *   openlawsvpn/linux/src/libopenlawsvpn.cpp  (submodule)
 *   openlawsvpn/linux/src/saml_capture.cpp    (submodule)
 *   openvpn3-core/...                         (submodule of submodule)
 *
 * Threading: clientConnectPhase1 and clientConnectPhase2 block until the
 * respective phase completes. Both MUST be called from a background thread
 * (Dispatchers.IO). All JNI callbacks (buildTun, protectSocket, onVpnLog)
 * are invoked from openvpn3-core internal threads — no UI operations allowed.
 */
object LibOpenLawsVpn {

    init {
        System.loadLibrary("openlawsvpn_jni")
    }

    /**
     * Create a new VPN client instance.
     *
     * @param configPath  Path to the .ovpn config file (app-private temp file).
     * @param service     VpnConnectionService instance — the JNI bridge stores a
     *                    global reference and calls back on this object for tun
     *                    creation, socket protection, and log forwarding.
     * @return opaque handle (pointer to ClientHandle), or -1 on failure.
     */
    external fun clientNew(configPath: String, service: Any): Long

    /** Free all resources associated with the handle. */
    external fun clientFree(handle: Long)

    /**
     * Phase 1: connect to trigger the SAML challenge.
     * Blocks ~3 s. Returns JSON string:
     *   {"saml_url":"…","state_id":"…","remote_ip":"…"}
     * or null on failure.
     */
    external fun clientConnectPhase1(handle: Long): String?

    /**
     * Phase 2: authenticate with the captured SAMLResponse.
     * Blocks until the CONNECTED event fires (tunnel up) or throws.
     */
    external fun clientConnectPhase2(
        handle: Long,
        stateId: String,
        token: String,
        remoteIp: String,
    )

    /**
     * Blocks until the tunnel disconnects.
     * Returns true if the session expired and SAML re-auth is needed.
     */
    external fun clientWaitForDisconnect(handle: Long): Boolean

    /** Disconnect and stop the tunnel. */
    external fun clientDisconnect(handle: Long)
}
