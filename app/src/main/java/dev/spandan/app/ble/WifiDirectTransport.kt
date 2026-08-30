package dev.spandan.app.ble

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "WifiDirectTransport"
private const val PORT = 8988

/**
 * Same-room, single-hop bulk payload delivery (personal card, voicenotes,
 * images) -- deliberately not part of the BLE flood-relay mesh (see
 * CLAUDE.md: multi-hop bulk transfer over Wi-Fi Direct is a much bigger
 * problem than BLE broadcast and isn't attempted here). Connects to the
 * first discovered peer and pushes one length-prefixed byte payload.
 *
 * Unverified on real hardware in this session: Wi-Fi Direct group
 * negotiation is finicky even under good conditions, and two-device manual
 * verification of it wasn't reached in the time available. Built correctly
 * against the documented API, but flag this honestly rather than claim
 * confidence the session didn't earn.
 */
class WifiDirectTransport(context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, context.mainLooper, null)

    @SuppressLint("MissingPermission")
    fun discoverAndSend(payload: ByteArray, onResult: (success: Boolean, message: String) -> Unit) {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            onResult(false, "Wi-Fi Direct not available on this device")
            return
        }

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mgr.requestPeers(ch) { peers ->
                    val target = peers.deviceList.firstOrNull()
                    if (target == null) {
                        onResult(false, "no nearby Wi-Fi Direct peer found")
                        return@requestPeers
                    }
                    connectAndSend(mgr, ch, target, payload, onResult)
                }
            }

            override fun onFailure(reason: Int) {
                onResult(false, "peer discovery failed, code=$reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectAndSend(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        device: WifiP2pDevice,
        payload: ByteArray,
        onResult: (Boolean, String) -> Unit,
    ) {
        val config = android.net.wifi.p2p.WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Group-owner election is negotiated, not something either side
                // picks -- a hardcoded "the owner is always 192.168.49.1" guess
                // was wrong in testing (this device became owner at a different
                // address). requestConnectionInfo() gives the real address.
                mgr.requestConnectionInfo(ch) { info ->
                    if (!info.groupFormed) {
                        onResult(false, "group did not form")
                        return@requestConnectionInfo
                    }
                    if (info.isGroupOwner) {
                        // This device negotiated as host, not client -- it has no
                        // one to push to (the other side would need to connect
                        // in, which requires it to already be listening). Owner
                        // election is effectively random per attempt; retrying
                        // often flips it.
                        onResult(false, "this device became the host this time -- try again")
                        return@requestConnectionInfo
                    }
                    val ownerAddress = info.groupOwnerAddress
                    if (ownerAddress == null) {
                        onResult(false, "no host address available")
                        return@requestConnectionInfo
                    }
                    Thread {
                        runCatching {
                            val socket = Socket()
                            socket.connect(java.net.InetSocketAddress(ownerAddress, PORT), 8_000)
                            DataOutputStream(socket.getOutputStream()).use { out ->
                                out.writeInt(payload.size)
                                out.write(payload)
                            }
                            socket.close()
                            onResult(true, "sent ${payload.size} bytes")
                        }.onFailure { e ->
                            Log.w(TAG, "send failed", e)
                            onResult(false, "send failed: ${e.message}")
                        }
                    }.start()
                }
            }

            override fun onFailure(reason: Int) {
                onResult(false, "connect failed, code=$reason")
            }
        })
    }

    /** Group-owner-side receiver: call once, keeps listening until [stopReceiving]. */
    fun startReceiving(onPayload: (ByteArray) -> Unit) {
        val server = runCatching { ServerSocket(PORT) }.getOrElse {
            Log.w(TAG, "could not open receive socket", it)
            return
        }
        serverSocket = server
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                handleClient(client, onPayload)
            }
        }.start()
    }

    private fun handleClient(client: Socket, onPayload: (ByteArray) -> Unit) {
        runCatching {
            DataInputStream(client.getInputStream()).use { input ->
                val size = input.readInt()
                val buf = ByteArray(size)
                input.readFully(buf)
                onPayload(buf)
            }
        }.onFailure { Log.w(TAG, "receive failed", it) }
        runCatching { client.close() }
    }

    private var serverSocket: ServerSocket? = null

    fun stopReceiving() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
