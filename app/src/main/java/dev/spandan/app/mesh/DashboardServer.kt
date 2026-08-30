package dev.spandan.app.mesh

import android.util.Log
import dev.spandan.app.ui.screens.ReceivedSignal
import dev.spandan.mesh.BarometricAltitude
import dev.spandan.mesh.RiskTrend
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "DashboardServer"
private const val PORT = 8787

/**
 * Bare-bones dashboard: a raw HTTP server (no external library -- Android
 * doesn't ship one, and adding a dependency for a hackathon demo feature
 * isn't worth it) run on the gateway phone, browsable from a laptop on the
 * same local network (LAN/hotspot only -- this is not internet-facing, and
 * the project's no-internet core still holds since this serves local
 * signal data, not a cloud round-trip).
 *
 * One HTML page, one JSON endpoint the page polls every few seconds. This is
 * a demo aid, not a real responder dashboard -- the brief explicitly scopes
 * the real one out as a separate product.
 */
class DashboardServer(
    private val signalsProvider: () -> List<ReceivedSignal>,
    private val riskTrendProvider: (originId: Int) -> RiskTrend = { RiskTrend.STABLE },
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Int {
        val socket = ServerSocket(PORT)
        serverSocket = socket
        running = true
        Thread {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread { handle(client) }.start()
            }
        }.start()
        return PORT
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        runCatching {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val writer = PrintWriter(client.getOutputStream(), true)
            if (path.startsWith("/api/signals")) {
                writer.print(httpResponse("application/json", signalsJson()))
            } else {
                writer.print(httpResponse("text/html", INDEX_HTML))
            }
        }.onFailure { Log.w(TAG, "request handling failed", it) }
        runCatching { client.close() }
    }

    private fun signalsJson(): String {
        val items = signalsProvider().joinToString(",") { s ->
            val elevation = if (s.packet.baroValid) BarometricAltitude.estimate(s.packet.baroDeltaDeciHpa) else null
            val trend = riskTrendProvider(s.packet.originId)
            """{"origin":"0x${s.packet.originId.toString(16)}","hazard":"${s.packet.hazardCategory}","severity":${s.packet.severity},"acknowledged":${s.acknowledged},"lat":${if (s.packet.location.validFix) s.packet.location.latDegrees() else "null"},"lon":${if (s.packet.location.validFix) s.packet.location.lonDegrees() else "null"},"elevationM":${elevation?.meters ?: "null"},"elevationUncertaintyM":${elevation?.uncertaintyMeters ?: "null"},"trend":"$trend"}"""
        }
        return "[$items]"
    }

    private fun httpResponse(contentType: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return "HTTP/1.1 200 OK\r\nContent-Type: $contentType; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body"
    }

    companion object {
        private val INDEX_HTML = """
            <!doctype html><html><head><meta charset="utf-8"><title>Spandan dashboard</title>
            <style>
              body{background:#141414;color:#fff;font-family:sans-serif;padding:24px}
              h1{color:#f5f04a}
              table{width:100%;border-collapse:collapse}
              td,th{border:2px solid #fff;padding:8px;text-align:left}
              .acked{color:#35d4c7} .pending{color:#f2a63a} .worsening{color:#f2a63a} .improving{color:#35d4c7}
            </style></head><body>
            <h1>Spandan — received signals</h1>
            <table id="t"><thead><tr><th>Origin</th><th>Hazard</th><th>Severity</th><th>Position</th><th>Elevation</th><th>Trend</th><th>Status</th></tr></thead><tbody></tbody></table>
            <script>
            async function refresh() {
              const res = await fetch('/api/signals');
              const rows = await res.json();
              const body = document.querySelector('#t tbody');
              body.innerHTML = rows.map(r => `<tr><td>${'$'}{r.origin}</td><td>${'$'}{r.hazard}</td><td>${'$'}{r.severity}</td><td>${'$'}{r.lat ? r.lat.toFixed(5)+', '+r.lon.toFixed(5) : 'no fix'}</td><td>${'$'}{r.elevationM !== null ? (r.elevationM >= 0 ? '+' : '') + r.elevationM.toFixed(0) + 'm (±' + r.elevationUncertaintyM.toFixed(0) + 'm)' : 'no reading'}</td><td class="${'$'}{r.trend.toLowerCase()}">${'$'}{r.trend !== 'STABLE' ? r.trend : ''}</td><td class="${'$'}{r.acknowledged ? 'acked' : 'pending'}">${'$'}{r.acknowledged ? 'ACKED' : 'PENDING'}</td></tr>`).join('');
            }
            refresh();
            setInterval(refresh, 3000);
            </script>
            </body></html>
        """.trimIndent()
    }
}
