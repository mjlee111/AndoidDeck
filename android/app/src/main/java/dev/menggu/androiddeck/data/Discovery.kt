package dev.menggu.androiddeck.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Finds AndroidDeck servers with a UDP broadcast; servers answer with a unicast JSON reply. */
object Discovery {
    private val REQUEST = """{"t":"androiddeck.discover"}""".toByteArray()

    suspend fun scan(listenMs: Long = 1500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredServer>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 250
                val targets = broadcastAddresses()
                repeat(2) { // UDP is lossy; send twice
                    for (addr in targets) {
                        runCatching { sock.send(DatagramPacket(REQUEST, REQUEST.size, addr, DISCOVERY_PORT)) }
                    }
                }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + listenMs
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val parsed = runCatching {
                        val obj = DeckJson.parseToJsonElement(text).jsonObject
                        if (obj["t"]?.jsonPrimitive?.contentOrNull != "androiddeck.here") null
                        else DeckJson.decodeFromJsonElement(ServerInfo.serializer(), obj)
                    }.getOrNull() ?: continue
                    val host = pkt.address.hostAddress ?: continue
                    found.putIfAbsent(parsed.id, DiscoveredServer(parsed, host, System.currentTimeMillis()))
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    ia.broadcast?.let { out += it }
                }
            }
        }
        return out.distinct()
    }
}
