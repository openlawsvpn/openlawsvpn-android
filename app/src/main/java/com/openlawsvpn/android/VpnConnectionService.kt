// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.openlawsvpn.android.jni.LibOpenLawsVpn
import com.openlawsvpn.android.model.ConnectionState
import com.openlawsvpn.android.model.VpnProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Core VPN service. Lifecycle:
 *
 *   1. Activity calls VpnService.prepare() and starts this service with ACTION_CONNECT.
 *   2. Service runs Phase 1 via JNI → receives SAML URL.
 *   3. Service emits samlUrlEvent — UI opens Chrome Custom Tab.
 *   4. SamlCallbackServer captures SAMLResponse on :35001.
 *   5. Service runs Phase 2 via JNI → blocks until CONNECTED.
 *   6. Tunnel is up. Service waits for disconnect (blocking) in background.
 *   7. On NEED_CREDS / session expiry → emits NeedReauth, UI re-triggers auth.
 *
 * The JNI bridge calls back to this service (on C++ threads) for:
 *   buildTun(json)     — configure VpnService.Builder, call establish(), return fd
 *   protectSocket(fd)  — call VpnService.protect() to avoid routing loops
 *   onVpnLog(msg)      — forward C++ log lines to logOutput flow
 */
class VpnConnectionService : VpnService() {

    // ── Service state (companion object → observable from any Activity/ViewModel) ──

    companion object {
        private val _state     = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        private val _log       = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 300)
        private val _samlUrl   = MutableSharedFlow<String>(extraBufferCapacity = 1)

        val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()
        val logOutput:       SharedFlow<String>         = _log.asSharedFlow()
        /** Emitted when a SAML URL is ready — UI should open Chrome Custom Tab. */
        val samlUrlEvent:    SharedFlow<String>         = _samlUrl.asSharedFlow()

        const val ACTION_CONNECT    = "com.openlawsvpn.android.CONNECT"
        const val ACTION_DISCONNECT = "com.openlawsvpn.android.DISCONNECT"
        const val EXTRA_PROFILE_ID  = "profile_id"
        private const val NOTIF_CHANNEL = "vpn_status"
        private const val NOTIF_ID      = 1
    }

    // ── Binder (for Activity to call connect/disconnect directly) ──────────────

    inner class LocalBinder : Binder() {
        fun getService(): VpnConnectionService = this@VpnConnectionService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── Internal state ─────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val samlServer   = SamlCallbackServer()
    private val profiles     get() = ProfileManager(this)
    private val timeFmt      = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var vpnHandle: Long = 0L
    private var activePfd: ParcelFileDescriptor? = null
    private var configFile: File? = null
    private var connectJob: Job? = null
    /** Tracks the background coroutine blocking on clientWaitForDisconnect.
     *  Must be joined before clientFree to avoid use-after-free in native code. */
    private var waitJob: Job? = null

    @Volatile private var disconnectedByNetworkLoss = false

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (_state.value !is ConnectionState.Connected) return
            // Only react if there is genuinely no remaining internet (not just a WiFi→LTE handoff).
            val active = connectivityManager.activeNetwork
            val caps  = active?.let { connectivityManager.getNetworkCapabilities(it) }
            val hasInternet = caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!hasInternet) {
                log("Network connectivity lost — VPN tunnel interrupted.")
                disconnectedByNetworkLoss = true
                serviceScope.launch {
                    if (vpnHandle != 0L)
                        withContext(Dispatchers.IO) { LibOpenLawsVpn.clientDisconnect(vpnHandle) }
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: run { stopSelf(); return START_NOT_STICKY }
                connectJob = serviceScope.launch { connect(id) }
            }
            ACTION_DISCONNECT -> serviceScope.launch { disconnect() }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // User revoked VPN permission through system settings.
        serviceScope.launch { disconnect() }
    }

    override fun onDestroy() {
        super.onDestroy()
        samlServer.stop()
        freeHandle()
        serviceScope.cancel()
    }

    // ── Connect flow ───────────────────────────────────────────────────────────

    private suspend fun connect(profileId: String) {
        val profile = profiles.getProfile(profileId)
        if (profile == null) {
            emitError("Profile $profileId not found"); return
        }

        try {
            _state.value = ConnectionState.Connecting(profile.name)
            log("Connecting to ${profile.name}…")

            startForeground(NOTIF_ID, buildNotification("Connecting…"))

            // Write config to app-private temp file (deleted in finally).
            configFile = profiles.writeTempConfig(profile)

            // Allocate native client — registers tun/protect/log callbacks.
            vpnHandle = LibOpenLawsVpn.clientNew(configFile!!.absolutePath, this)
            if (vpnHandle == -1L) {
                emitError("JNI not ready — openvpn3-core not linked yet"); return
            }

            // Phase 1 — blocks ~3s on IO thread.
            log("Phase 1: requesting SAML challenge…")
            val phase1Json = withContext(Dispatchers.IO) {
                LibOpenLawsVpn.clientConnectPhase1(vpnHandle)
            } ?: run { emitError("Phase 1 failed"); return }

            val p1       = JSONObject(phase1Json)
            val samlUrl  = p1.getString("saml_url")
            val stateId  = p1.getString("state_id")
            val remoteIp = p1.getString("remote_ip")

            log("SAML URL received — opening browser…")
            _state.value = ConnectionState.WaitingSaml(profile.name)
            updateNotification("Waiting for SAML login…")

            // Start ACS server and signal UI to open the browser.
            samlServer.start(serviceScope) { event -> handleSamlEvent(event, stateId, remoteIp, profile) }
            _samlUrl.emit(samlUrl)

        } catch (e: Exception) {
            configFile?.delete()
            emitError(e.message ?: "Connection error")
        }
    }

    private fun handleSamlEvent(
        event: SamlCallbackServer.Event,
        stateId: String,
        remoteIp: String,
        profile: VpnProfile,
    ) {
        when (event) {
            is SamlCallbackServer.Event.TokenReceived ->
                serviceScope.launch { phase2(stateId, event.samlResponse, remoteIp, profile) }
            is SamlCallbackServer.Event.Error ->
                serviceScope.launch { emitError("SAML server: ${event.message}") }
            else -> {}
        }
    }

    private suspend fun phase2(
        stateId: String,
        token: String,
        remoteIp: String,
        profile: VpnProfile,
    ) {
        samlServer.stop()
        log("Phase 2: authenticating with SAML token (${token.length} chars)…")
        try {
            withContext(Dispatchers.IO) {
                LibOpenLawsVpn.clientConnectPhase2(vpnHandle, stateId, token, remoteIp)
            }
            // clientConnectPhase2 returns only after CONNECTED event.
            _state.value = ConnectionState.Connected(profile.name, remoteIp, "")
            log("Tunnel up — connected to ${profile.name}.")
            updateNotification("Connected — ${profile.name}")
            // Watch for network loss so we don't show "Connected" when the tunnel is dead.
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(), networkCallback
            )

            // Wait for disconnect in background.
            // Save the job so disconnect() can join() it before calling clientFree —
            // otherwise clientFree races with clientWaitForDisconnect's condition_variable.
            waitJob = serviceScope.launch(Dispatchers.IO) {
                val needsReauth = LibOpenLawsVpn.clientWaitForDisconnect(vpnHandle)
                withContext(Dispatchers.Main) {
                    when {
                        disconnectedByNetworkLoss -> {
                            log("Disconnected — network was lost.")
                            _state.value = ConnectionState.NeedReauth(
                                profile.name,
                                "Network was lost — tap Connect to reconnect."
                            )
                        }
                        needsReauth -> {
                            log("Session expired — re-authentication required.")
                            _state.value = ConnectionState.NeedReauth(profile.name)
                        }
                        else -> {
                            log("Disconnected.")
                            _state.value = ConnectionState.Idle
                        }
                    }
                    disconnectedByNetworkLoss = false
                    waitJob = null
                    cleanup()
                }
            }
        } catch (e: Exception) {
            emitError(e.message ?: "Phase 2 error")
        }
    }

    suspend fun disconnect() {
        if (vpnHandle != 0L) {
            _state.value = ConnectionState.Disconnecting
            withContext(Dispatchers.IO) { LibOpenLawsVpn.clientDisconnect(vpnHandle) }
        }
        // Join the background wait coroutine before calling clientFree.
        // clientWaitForDisconnect blocks on a native condition_variable; freeing the
        // handle while that thread is still inside the mutex causes SIGABRT / SIGSEGV.
        waitJob?.join()
        cleanup()
        _state.value = ConnectionState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        samlServer.stop()
        freeHandle()
        activePfd?.close(); activePfd = null
        configFile?.delete(); configFile = null
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    private fun freeHandle() {
        if (vpnHandle != 0L) {
            LibOpenLawsVpn.clientFree(vpnHandle)
            vpnHandle = 0L
        }
    }

    // ── JNI callbacks (invoked from C++ threads) ───────────────────────────────

    /**
     * Called by jni_bridge when openvpn3-core needs the tun interface.
     * Parses the TunConfig JSON, configures VpnService.Builder, and returns the fd.
     * This runs on a C++ thread — no coroutine context available.
     */
    @Suppress("unused") // called reflectively from JNI
    fun buildTun(tunConfigJson: String): Int {
        return try {
            val j = JSONObject(tunConfigJson)
            val builder = Builder()
                .setSession(j.optString("session_name", "openlawsvpn"))
                .setMtu(j.optInt("mtu", 1500))

            j.getJSONArray("tunnel_addresses").forEachObject {
                builder.addAddress(it.getString("address"), it.getInt("prefix"))
            }

            if (j.optBoolean("reroute_gw_ipv4")) {
                builder.addRoute("0.0.0.0", 0)
            } else {
                j.getJSONArray("routes").forEachObject {
                    if (!it.getBoolean("ipv6"))
                        builder.addRoute(it.getString("address"), it.getInt("prefix"))
                }
            }
            if (j.optBoolean("reroute_gw_ipv6")) {
                builder.addRoute("::", 0)
            } else {
                j.getJSONArray("routes").forEachObject {
                    if (it.getBoolean("ipv6"))
                        builder.addRoute(it.getString("address"), it.getInt("prefix"))
                }
            }

            j.getJSONArray("dns_servers").forEachString { builder.addDnsServer(it) }
            j.getJSONArray("search_domains").forEachString { builder.addSearchDomain(it) }

            val pfd = builder.establish() ?: return -1
            activePfd?.close()
            activePfd = pfd
            pfd.detachFd()
        } catch (e: Exception) {
            log("[ERROR] buildTun failed: ${e.message}")
            -1
        }
    }

    /** Called by jni_bridge for each VPN socket — must protect it from routing loops. */
    @Suppress("unused") // called reflectively from JNI
    fun protectSocket(fd: Int): Boolean = protect(fd)

    /** Called by jni_bridge to forward C++ log lines. */
    @Suppress("unused") // called reflectively from JNI
    fun onVpnLog(msg: String) { log(msg.trimEnd()) }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        val ts = timeFmt.format(Date())
        serviceScope.launch { _log.emit("[$ts] $msg") }
    }

    private fun emitError(msg: String) {
        log("[ERROR] $msg")
        _state.value = ConnectionState.Error(msg)
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "VPN Status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openAppPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectPi = PendingIntent.getService(
            this, 1,
            Intent(this, VpnConnectionService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("openlawsvpn")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(openAppPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Disconnect", disconnectPi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}

// Extension helpers to iterate JSONArray cleanly.
private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) block(getJSONObject(i))
}
private fun JSONArray.forEachString(block: (String) -> Unit) {
    for (i in 0 until length()) block(getString(i))
}
