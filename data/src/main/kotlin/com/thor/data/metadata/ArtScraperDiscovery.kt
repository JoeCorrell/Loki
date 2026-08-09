package com.thor.data.metadata

import com.thor.core.common.dispatchers.Dispatcher
import com.thor.core.common.dispatchers.ThorDispatcher
import com.thor.core.common.log.ThorLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** One ArtScraper companion that answered a probe. */
data class ArtScraperHost(
    /** Dotted-quad address it replied from, which is what to connect to. */
    val address: String,
    val port: Int,
    /** The PC's own name, for showing in a list a person has to choose from. */
    val machineName: String,
    /** False when the companion is running without its metadata database imported. */
    val hasLaunchBox: Boolean,
) {
    /** What goes into `MetadataSettings.artScraperHost`. */
    val hostAndPort: String get() = "$address:$port"
}

/**
 * Finds ArtScraper companions on the local network.
 *
 * Asking beats making somebody read their PC's IP address off one screen and type it into
 * another with a thumbstick — and an address typed by hand is one that breaks the next time
 * DHCP moves it.
 *
 * A broadcast probe with unicast replies, matching the beacon in `artscraper serve`. That
 * arrangement is deliberate on Android: receiving broadcast or multicast traffic needs a
 * `WifiManager.MulticastLock` held, with the battery cost that implies, while receiving the
 * reply to a packet this device sent itself needs nothing at all.
 */
@Singleton
class ArtScraperDiscovery @Inject constructor(
    private val json: Json,
    @Dispatcher(ThorDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Probes and collects whatever answers within [timeoutMs].
     *
     * One shot rather than a flow: this runs behind a "Find my PC" button, where the useful
     * behaviour is to look for a couple of seconds and report, not to listen indefinitely.
     * Returns an empty list rather than throwing — a network that drops broadcast traffic is an
     * ordinary outcome that the manual field exists for.
     */
    suspend fun discover(timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<ArtScraperHost> =
        withContext(ioDispatcher) {
            val found = linkedMapOf<String, ArtScraperHost>()

            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = POLL_MS

                    val probe = PROBE.toByteArray()
                    for (target in broadcastAddresses()) {
                        runCatching {
                            socket.send(DatagramPacket(probe, probe.size, InetSocketAddress(target, PORT)))
                        }
                    }

                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buffer = ByteArray(512)

                    while (System.currentTimeMillis() < deadline) {
                        coroutineContext.ensureActive()

                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            // Nothing yet; the deadline decides when to stop, not this.
                            continue
                        }

                        val host = parse(packet) ?: continue
                        found[host.hostAndPort] = host
                    }
                }
            } catch (e: Exception) {
                ThorLog.w(TAG, "Discovery failed", e)
            }

            found.values.toList()
        }

    private fun parse(packet: DatagramPacket): ArtScraperHost? {
        val body = String(packet.data, packet.offset, packet.length)
        val reply = runCatching { json.decodeFromString<Reply>(body) }.getOrNull() ?: return null
        if (reply.service != "artscraper" || reply.port !in 1..65535) return null

        return ArtScraperHost(
            address = packet.address.hostAddress ?: return null,
            port = reply.port,
            machineName = reply.host.ifBlank { packet.address.hostAddress.orEmpty() },
            hasLaunchBox = reply.launchBox,
        )
    }

    /**
     * Where to send the probe.
     *
     * The interface's own broadcast address as well as 255.255.255.255, because the global one
     * is dropped by a good many access points while the subnet-directed one gets through. Both
     * cost a single datagram, and a duplicate reply collapses on the address key anyway.
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()

        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    address.broadcast?.let(targets::add)
                }
            }
        }

        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        return targets
    }

    @Serializable
    private data class Reply(
        val service: String = "",
        val protocol: Int = 0,
        val port: Int = 0,
        val host: String = "",
        val launchBox: Boolean = false,
    )

    private companion object {
        const val TAG = "ArtScraperDiscovery"

        /** Must match `DiscoveryBeacon.Probe` on the desktop, byte for byte. */
        const val PROBE = "ARTSCRAPER-DISCOVER-V1"
        const val PORT = 8757

        /**
         * Long enough for a desktop to wake its network stack and answer, short enough that a
         * person does not wonder whether the button worked.
         */
        const val DEFAULT_TIMEOUT_MS = 2_000

        /** How long one receive blocks before the deadline is rechecked. */
        const val POLL_MS = 250
    }
}
