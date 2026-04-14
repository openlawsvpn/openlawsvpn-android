// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2024 openlawsvpn contributors
//
// JNI bridge between Kotlin VpnConnectionService and libopenlawsvpn (C++).
//
// Design:
//   Kotlin calls clientNew(configPath, service) → allocates ClientHandle,
//   registers two lambdas into libopenlawsvpn:
//
//     tun_establish_fn  — called by openvpn3-core on tun creation;
//                         calls back to Java VpnConnectionService.buildTun(json)
//                         which runs VpnService.Builder.establish() and returns the fd.
//
//     socket_protect_fn — called for each VPN socket before it connects;
//                         calls back to Java VpnService.protect(fd) to prevent
//                         traffic from being routed back through the tunnel.
//
//   All callbacks are invoked from openvpn3-core internal threads; the bridge
//   attaches each to the JVM before calling and detaches after.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <memory>
#include "libopenlawsvpn.h"

#define TAG "openlawsvpn_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── JVM reference (set in JNI_OnLoad) ────────────────────────────────────────

static JavaVM* g_jvm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// ── Per-client handle ─────────────────────────────────────────────────────────

struct ClientHandle {
    openlawsvpn::OpenVPNClient* client  = nullptr;
    jobject                     svc_ref = nullptr;  // global ref to VpnConnectionService
    jmethodID                   mid_build_tun     = nullptr;
    jmethodID                   mid_protect       = nullptr;
    jmethodID                   mid_log           = nullptr;
};

// ── Thread-attach helpers ─────────────────────────────────────────────────────

static JNIEnv* attach() {
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("AttachCurrentThread failed");
        return nullptr;
    }
    return env;
}
static void detach() { g_jvm->DetachCurrentThread(); }

// ── TunConfig → JSON (no external deps) ──────────────────────────────────────

static std::string tun_config_to_json(const openlawsvpn::TunConfig& cfg) {
    auto addr_array = [](const std::vector<openlawsvpn::TunConfig::IpNet>& v) {
        std::ostringstream s;
        s << '[';
        for (size_t i = 0; i < v.size(); ++i) {
            if (i) s << ',';
            s << "{\"address\":\"" << v[i].address << "\","
              << "\"prefix\":"     << v[i].prefix_length << ","
              << "\"ipv6\":"       << (v[i].ipv6 ? "true" : "false") << '}';
        }
        s << ']';
        return s.str();
    };
    auto str_array = [](const std::vector<std::string>& v) {
        std::ostringstream s;
        s << '[';
        for (size_t i = 0; i < v.size(); ++i) {
            if (i) s << ',';
            s << '"' << v[i] << '"';
        }
        s << ']';
        return s.str();
    };

    std::ostringstream j;
    j << '{'
      << "\"mtu\":"             << cfg.mtu             << ','
      << "\"session_name\":\""  << cfg.session_name    << "\","
      << "\"reroute_gw_ipv4\":" << (cfg.reroute_gw_ipv4 ? "true" : "false") << ','
      << "\"reroute_gw_ipv6\":" << (cfg.reroute_gw_ipv6 ? "true" : "false") << ','
      << "\"tunnel_addresses\":" << addr_array(cfg.tunnel_addresses) << ','
      << "\"routes\":"           << addr_array(cfg.routes)           << ','
      << "\"dns_servers\":"      << str_array(cfg.dns_servers)       << ','
      << "\"search_domains\":"   << str_array(cfg.search_domains)
      << '}';
    return j.str();
}

// ── JNI methods ───────────────────────────────────────────────────────────────

extern "C" {

/**
 * clientNew(configPath: String, service: VpnConnectionService): Long
 *
 * Creates an OpenVPNClient, registers the tun/protect/log callbacks,
 * and returns an opaque handle (pointer to ClientHandle).
 *
 * @param jService  VpnConnectionService instance — must expose:
 *                    fun buildTun(json: String): Int
 *                    fun protectSocket(fd: Int): Boolean
 *                    fun onVpnLog(msg: String)
 */
JNIEXPORT jlong JNICALL
Java_com_openlawsvpn_android_jni_LibOpenLawsVpn_clientNew(
        JNIEnv* env, jobject, jstring jConfigPath, jobject jService) {
    const char* path = env->GetStringUTFChars(jConfigPath, nullptr);
    auto* h = new ClientHandle();
    h->client  = new openlawsvpn::OpenVPNClient(path);
    h->svc_ref = env->NewGlobalRef(jService);
    env->ReleaseStringUTFChars(jConfigPath, path);

    h->client->set_connect_mode(openlawsvpn::ConnectMode::DIRECT);

    // Cache method IDs on the calling (main) thread — valid for the lifetime of the class.
    jclass cls = env->GetObjectClass(jService);
    h->mid_build_tun = env->GetMethodID(cls, "buildTun",      "(Ljava/lang/String;)I");
    h->mid_protect   = env->GetMethodID(cls, "protectSocket", "(I)Z");
    h->mid_log       = env->GetMethodID(cls, "onVpnLog",      "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    // Tun establish — called from openvpn3-core thread during Phase 2 connect.
    h->client->set_tun_establish_fn([h](const openlawsvpn::TunConfig& cfg) -> int {
        JNIEnv* e = attach();
        if (!e) return -1;
        std::string json = tun_config_to_json(cfg);
        jstring j = e->NewStringUTF(json.c_str());
        jint fd = e->CallIntMethod(h->svc_ref, h->mid_build_tun, j);
        e->DeleteLocalRef(j);
        detach();
        LOGI("tun_builder_establish → fd=%d", (int)fd);
        return (int)fd;
    });

    // Socket protect — called for each VPN socket (UDP/TCP) before connecting.
    h->client->set_socket_protect_fn([h](int fd, const std::string&, bool) -> bool {
        JNIEnv* e = attach();
        if (!e) return false;
        jboolean ok = e->CallBooleanMethod(h->svc_ref, h->mid_protect, (jint)fd);
        detach();
        return (bool)ok;
    });

    // Log — forward to Kotlin service (which emits to UI StateFlow).
    h->client->set_log_callback([](const char* msg, void* ud) {
        auto* h = static_cast<ClientHandle*>(ud);
        JNIEnv* e = attach();
        if (!e) return;
        jstring j = e->NewStringUTF(msg);
        e->CallVoidMethod(h->svc_ref, h->mid_log, j);
        e->DeleteLocalRef(j);
        detach();
    }, h);

    LOGI("clientNew: handle=%p", (void*)h);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_openlawsvpn_android_jni_LibOpenLawsVpn_clientFree(
        JNIEnv* env, jobject, jlong handle) {
    auto* h = reinterpret_cast<ClientHandle*>(handle);
    if (!h) return;
    delete h->client;
    env->DeleteGlobalRef(h->svc_ref);
    delete h;
    LOGI("clientFree: done");
}

/**
 * clientConnectPhase1(handle: Long): String?
 * Blocks until SAML challenge is received. Returns JSON:
 *   {"saml_url":"…","state_id":"…","remote_ip":"…"}
 * or null on failure. Must be called from a background thread.
 */
JNIEXPORT jstring JNICALL
Java_com_openlawsvpn_android_jni_LibOpenLawsVpn_clientConnectPhase1(
        JNIEnv* env, jobject, jlong handle) {
    auto* h = reinterpret_cast<ClientHandle*>(handle);
    if (!h) return nullptr;
    try {
        auto res = h->client->connect_phase1();
        std::ostringstream j;
        j << "{\"saml_url\":\""  << res.saml_url  << "\","
          << "\"state_id\":\""   << res.state_id  << "\","
          << "\"remote_ip\":\""  << res.remote_ip << "\"}";
        return env->NewStringUTF(j.str().c_str());
    } catch (const std::exception& e) {
        LOGE("clientConnectPhase1 error: %s", e.what());
        return nullptr;
    }
}

/**
 * clientConnectPhase2(handle, stateId, token, remoteIp)
 * Blocks until CONNECTED. Throws a Java RuntimeException on auth failure or timeout.
 * Must be called from a background thread.
 */
JNIEXPORT void JNICALL
Java_com_openlawsvpn_android_jni_LibOpenLawsVpn_clientConnectPhase2(
        JNIEnv* env, jobject, jlong handle,
        jstring jStateId, jstring jToken, jstring jRemoteIp) {
    auto* h = reinterpret_cast<ClientHandle*>(handle);
    if (!h) return;

    // Copy to std::string immediately so JNI strings are always released.
    const char* raw;
    raw = env->GetStringUTFChars(jStateId,  nullptr); std::string stateId(raw);  env->ReleaseStringUTFChars(jStateId,  raw);
    raw = env->GetStringUTFChars(jToken,    nullptr); std::string token(raw);    env->ReleaseStringUTFChars(jToken,    raw);
    raw = env->GetStringUTFChars(jRemoteIp, nullptr); std::string remoteIp(raw); env->ReleaseStringUTFChars(jRemoteIp, raw);

    try {
        h->client->connect_phase2(stateId, token, remoteIp);
    } catch (const std::exception& ex) {
        LOGE("clientConnectPhase2: %s", ex.what());
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), ex.what());
    }
}

/**
 * clientWaitForDisconnect(handle): Boolean
 * Blocks until the tunnel drops. Returns true if SAML re-auth is needed.
 */
JNIEXPORT jboolean JNICALL
Java_com_openlawsvpn_android_jni_LibOpenLawsVpn_clientWaitForDisconnect(
        JNIEnv* env, jobject, jlong handle) {
    auto* h = reinterpret_cast<ClientHandle*>(handle);
    if (!h) return JNI_FALSE;
    try {
        return h->client->wait_for_disconnect() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& ex) {
        LOGE("clientWaitForDisconnect: %s", ex.what());
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), ex.what());
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_openlawsvpn_android_jni_LibOpenLawsVpn_clientDisconnect(
        JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<ClientHandle*>(handle);
    if (h && h->client) h->client->disconnect();
}

} // extern "C"
