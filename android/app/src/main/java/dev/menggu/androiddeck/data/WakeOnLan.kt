package dev.menggu.androiddeck.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/** Sends Wake-on-LAN magic packets (6 x 0xFF followed by the MAC address 16 times). */
object WakeOnLan {
    suspend fun send(server: SavedServer): Result<Unit> {
        val mac = server.mac ?: return Result.failure(
            IllegalStateException("${server.name}'s network address is unknown. Connect to it once first."),
        )
        return send(mac, server.broadcast)
    }

    suspend fun send(mac: String, broadcast: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }
            require(bytes.size == 6) { "Invalid MAC address: $mac" }
            val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
            val targets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                broadcast?.let { runCatching { add(InetAddress.getByName(it)) } }
                runCatching {
                    for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                        if (!ni.isUp || ni.isLoopback) continue
                        ni.interfaceAddresses.mapNotNull { it.broadcast }.forEach { add(it) }
                    }
                }
            }.distinct()
            DatagramSocket().use { sock ->
                sock.broadcast = true
                repeat(3) {
                    for (addr in targets) for (port in intArrayOf(9, 7)) {
                        runCatching { sock.send(DatagramPacket(packet, packet.size, addr, port)) }
                    }
                }
            }
        }
    }
}
