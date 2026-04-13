package com.openlawsvpn.android

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant

/**
 * Minimal HTTP server on 127.0.0.1:35001 — the SAML ACS endpoint.
 *
 * AWS Client VPN hardcodes AssertionConsumerServiceURL=http://127.0.0.1:35001.
 * After the user completes IdP login in Chrome Custom Tabs, the AWS SSO SPA
 * form-POSTs the SAMLResponse here. Validated on a physical device (2026-04-13):
 * Chrome does not block the HTTPS→HTTP loopback form POST.
 *
 * POST / → captures SAMLResponse, redirects to openlawsvpn://saml-callback
 * OPTIONS / → CORS preflight (Private Network Access headers)
 * GET / → status page
 */
class SamlCallbackServer {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    sealed class Event {
        object Started : Event()
        object Stopped : Event()
        data class RequestReceived(val method: String, val path: String, val snippet: String) : Event()
        data class TokenReceived(val samlResponse: String) : Event()
        data class Error(val message: String) : Event()
    }

    fun start(scope: CoroutineScope, onEvent: (Event) -> Unit) {
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    // Explicit IPv4 — InetAddress.getLoopbackAddress() may return ::1.
                    bind(InetSocketAddress(Inet4Address.getByName("127.0.0.1"), PORT))
                }
                onEvent(Event.Started)
                while (isActive) {
                    val client = serverSocket!!.accept()
                    launch { handleClient(client, onEvent) }
                }
            } catch (e: Exception) {
                if (isActive) onEvent(Event.Error(e.message ?: "Server error"))
            }
        }
    }

    private fun handleClient(socket: Socket, onEvent: (Event) -> Unit) {
        socket.use {
            try {
                val reader = BufferedReader(InputStreamReader(it.inputStream))
                val output = it.outputStream
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "?" }
                val path   = parts.getOrElse(1) { "/" }

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0 && line.substring(0, colonIdx).trim().lowercase() == "content-length")
                        contentLength = line.substring(colonIdx + 1).trim().toIntOrNull() ?: 0
                }

                val body = if (contentLength > 0) {
                    val chars = CharArray(minOf(contentLength, 65536))
                    val read = reader.read(chars, 0, chars.size)
                    if (read > 0) String(chars, 0, read) else ""
                } else ""

                onEvent(Event.RequestReceived(method, path, body.take(120)))

                when {
                    method == "OPTIONS" -> output.write(buildResponse(204, mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "Content-Type",
                        "Access-Control-Allow-Private-Network" to "true",
                    ), ""))
                    method == "POST" && path == "/" -> {
                        val samlResponse = body.split("&")
                            .find { it.startsWith("SAMLResponse=") }
                            ?.removePrefix("SAMLResponse=")
                            ?.let { t -> URLDecoder.decode(t, "UTF-8") }
                            ?.let { t -> normalizeBase64(t) }

                        if (samlResponse != null) {
                            // Always redirect to close the Custom Tab regardless of staleness.
                            output.write(buildResponse(302, mapOf(
                                "Location" to "openlawsvpn://saml-callback",
                                "Access-Control-Allow-Origin" to "*",
                                "Access-Control-Allow-Private-Network" to "true",
                            ), ""))
                            val ageMin = samlResponseAgeMinutes(samlResponse)
                            if (ageMin != null && ageMin > SAML_MAX_AGE_MINUTES) {
                                Log.w(TAG, "Rejecting SAML assertion issued ${ageMin}min ago (max $SAML_MAX_AGE_MINUTES min) — likely a cached Chrome form POST")
                                onEvent(Event.Error("SAML assertion is ${ageMin} min old (limit: $SAML_MAX_AGE_MINUTES min). Please connect again for a fresh login."))
                            } else {
                                onEvent(Event.TokenReceived(samlResponse))
                            }
                        } else {
                            output.write(buildResponse(400, emptyMap(), "Missing SAMLResponse"))
                        }
                    }
                    else -> {
                        val html = "<html><body><p>openlawsvpn ACS — port $PORT</p></body></html>"
                        output.write(buildResponse(200, mapOf(
                            "Content-Type" to "text/html; charset=utf-8",
                            "Access-Control-Allow-Private-Network" to "true",
                        ), html))
                    }
                }
                output.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Client error: ${e.message}")
            }
        }
    }

    private fun buildResponse(status: Int, headers: Map<String, String>, body: String): ByteArray {
        val statusText = mapOf(200 to "OK", 204 to "No Content", 302 to "Found", 400 to "Bad Request")
            .getOrDefault(status, "Unknown")
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("HTTP/1.1 $status $statusText\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (bodyBytes.isNotEmpty()) sb.append("Content-Length: ${bodyBytes.size}\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray() + (if (bodyBytes.isNotEmpty()) bodyBytes else byteArrayOf())
    }

    fun stop() {
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null
    }

    companion object {
        private const val TAG  = "SamlServer"
        const val PORT = 35001
        private const val SAML_MAX_AGE_MINUTES = 5L

        /**
         * Mirrors saml_capture.cpp normalize_base64():
         *
         * Chrome submits SAMLResponse as application/x-www-form-urlencoded. In this encoding
         * '+' represents SPACE, so base64 '+' characters that Chrome does not percent-encode
         * arrive in the POST body as raw '+', which URLDecoder.decode() then converts to SPACE.
         * This step converts those spaces back to '+' and handles URL-safe base64 (-/_),
         * strips non-base64 characters (newlines etc.), and re-adds padding.
         */
        fun normalizeBase64(input: String): String {
            val sb = StringBuilder(input.length)
            for (c in input) {
                when {
                    c == ' '                              -> sb.append('+')   // URLDecoded '+' → base64 '+'
                    c in 'A'..'Z' || c in 'a'..'z'
                            || c in '0'..'9' || c == '+' || c == '/' -> sb.append(c)
                    c == '-'                              -> sb.append('+')   // URL-safe → standard base64
                    c == '_'                              -> sb.append('/')   // URL-safe → standard base64
                    // strip '=', '\n', '\r', and anything else — padding re-added below
                }
            }
            while (sb.length % 4 != 0) sb.append('=')
            return sb.toString()
        }

        /**
         * Extracts IssueInstant from a base64-encoded SAML Response and returns how many
         * minutes old it is, or null if the timestamp cannot be determined.
         * AWS SAMLResponses contain IssueInstant="2026-04-14T00:04:40Z" in the root element.
         */
        fun samlResponseAgeMinutes(base64SamlResponse: String): Long? = try {
            val xml = String(Base64.decode(base64SamlResponse, Base64.DEFAULT), Charsets.UTF_8)
            val match = Regex("""IssueInstant="([^"]+)"""").find(xml) ?: return null
            val issued = Instant.parse(match.groupValues[1])
            Duration.between(issued, Instant.now()).toMinutes()
        } catch (e: Exception) {
            null  // can't parse — don't reject
        }
    }
}
