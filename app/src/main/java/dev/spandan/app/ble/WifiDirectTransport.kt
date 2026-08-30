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
 * problem than BLE broadcast and isn't attempted here).
 *
 * Bidirectional as a single symmetric exchange, not a persistent duplex
 * channel: whichever side connects out writes its payload, then reads a
 * reply on the *same* socket before closing; whichever side is listening
 * reads the incoming payload, then writes its own reply back before
 * closing. One round trip per connection, not a live back-and-forth session
 * -- e.g. a victim's card triggers an automatic reply in the same exchange,
 * rather than a second separate connect attempt in the other direction.
 *
 * Unverified on real hardware in this session: Wi-Fi Direct group
 * negotiation is finicky even under good conditions, and two-device manual
 * verification of the original one-way version was reached, but this
 * symmetric-exchange revision was not re-verified before this commit. Built
 * correctly against the documented API; flag this honestly rather than
 * claim confidence the session didn't earn.
 */
class WifiDirectTransport(context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, context.mainLooper, null)

    /**
     * @param onReply called with whatever the peer sent back in the same
     *   exchange; a zero-length array if the peer had nothing to reply with.
     */
    @SuppressLint("MissingPermission")
    fun discoverAndSend(
        payload: ByteArray,
        onResult: (success: Boolean, message: String) -> Unit,
        onReply: (ByteArray) -> Unit = {},
    ) {
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
                    connectAndExchange(mgr, ch, target, payload, onResult, onReply)
                }
            }

            override fun onFailure(reason: Int) {
                onResult(false, "peer discovery failed, code=$reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectAndExchange(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        device: WifiP2pDevice,
        payload: ByteArray,
        onResult: (Boolean, String) -> Unit,
        onReply: (ByteArray) -> Unit,
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
                            val out = DataOutputStream(socket.getOutputStream())
                            val input = DataInputStream(socket.getInputStream())
                            out.writeInt(payload.size)
                            out.write(payload)
                            out.flush()
                            val replySize = input.readInt()
                            val reply = ByteArray(replySize)
                            if (replySize > 0) input.readFully(reply)
                            socket.close()
                            if (replySize > 0) onReply(reply)
                            onResult(true, "sent ${payload.size} bytes" + if (replySize > 0) ", received ${replySize} bytes back" else "")
                        }.onFailure { e ->
                            Log.w(TAG, "exchange failed", e)
                            onResult(false, "exchange failed: ${e.message}")
                        }
                    }.start()
                }
            }

            override fun onFailure(reason: Int) {
                onResult(false, "connect failed, code=$reason")
            }
        })
    }

    /**
     * Group-owner-side listener: call once, keeps listening until
     * [stopReceiving]. [replyProvider] is invoked after each payload is
     * fully received, and its return value is written back on the same
     * socket before closing -- return a zero-length array if there's
     * nothing to reply with.
     */
    fun startReceiving(replyProvider: () -> ByteArray = { ByteArray(0) }, onPayload: (ByteArray) -> Unit) {
        val server = runCatching { ServerSocket(PORT) }.getOrElse {
            Log.w(TAG, "could not open receive socket", it)
            return
        }
        serverSocket = server
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                handleClient(client, replyProvider, onPayload)
            }
        }.start()
    }

    private fun handleClient(client: Socket, replyProvider: () -> ByteArray, onPayload: (ByteArray) -> Unit) {
        runCatching {
            val input = DataInputStream(client.getInputStream())
            val out = DataOutputStream(client.getOutputStream())
            val size = input.readInt()
            val buf = ByteArray(size)
            input.readFully(buf)
            onPayload(buf)
            val reply = replyProvider()
            out.writeInt(reply.size)
            if (reply.isNotEmpty()) out.write(reply)
            out.flush()
        }.onFailure { Log.w(TAG, "receive/reply failed", it) }
        runCatching { client.close() }
    }

    private var serverSocket: ServerSocket? = null

    fun stopReceiving() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
