package com.thor.data.files

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.thor.core.common.dispatchers.Dispatcher
import com.thor.core.common.dispatchers.ThorDispatcher
import com.thor.core.common.log.ThorLog
import dagger.hilt.android.qualifiers.ApplicationContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds file servers on the network the device is joined to.
 *
 * Three probes, run together, because no one of them finds everything and each
 * fails on a different kind of network:
 *
 * - **A sweep of port 445** across the local subnet, at its real prefix. Crude,
 *   and the only method that needs nothing of the network but the ability to open
 *   a connection — no multicast, no broadcast, no service advertising. It is the
 *   backbone: if a server is reachable at all, this finds it. What it cannot do
 *   is say what the machine is *called*.
 * - **mDNS**, for `_smb._tcp`. Every NAS worth the name advertises it, and the
 *   announcement carries a proper name, so this is what turns `192.168.1.20`
 *   into `Tower`. Dead on networks that filter multicast, which is most public
 *   ones and some routers' guest modes.
 * - **A NetBIOS node-status query** to each address the sweep turned up. This is
 *   how a Windows machine or a Samba box says its name, and it is sent *to the
 *   host* rather than broadcast — so unlike the usual NetBIOS discovery it works
 *   on a network that drops broadcast traffic.
 *
 * Merged by address, so a server that answers all three appears once, named.
 *
 * A scan is a one-shot rather than a live flow, unlike the GameStream discovery
 * this sits beside. The reason is the sweep: 254 connection attempts is a fine
 * thing to do when somebody presses a button and an unpleasant one to leave
 * running behind a screen, and the result is a list to choose from rather than
 * something the user watches.
 */
@Singleton
class SmbDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(ThorDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /**
     * Everything that answered, named where anything would say.
     *
     * The two probes run concurrently and the whole thing is bounded, so this
     * takes about as long as its slowest part rather than the sum of them.
     */
    suspend fun scan(): List<DiscoveredServer> = withContext(ioDispatcher) {
        val found = LinkedHashMap<String, DiscoveredServer>()

        coroutineScope {
            val advertised = async { announced() }
            val reachable = async { sweep() }

            // Announced first: it is the one that arrives with a real name on it.
            advertised.await().forEach { found[it.address] = it }
            reachable.await().forEach { address ->
                if (address !in found) {
                    found[address] = DiscoveredServer(address = address, name = null)
                }
            }
        }

        named(found.values.toList())
    }

    // ---- The sweep ---------------------------------------------------------

    /** Every address on the local subnet with something listening on 445. */
    private suspend fun sweep(): List<String> {
        val addresses = sweepAddresses()
        if (addresses.isEmpty()) {
            ThorLog.w(TAG, "No local network to scan; skipping the sweep")
            return emptyList()
        }

        ThorLog.d(TAG) { "Sweeping ${addresses.size} addresses for port $SMB_PORT" }

        // Bounded rather than launched all at once. Each probe holds a thread
        // while it blocks on connect, and five hundred of those would take the IO
        // pool out from under everything else in the launcher for the duration.
        val probes = ioDispatcher.limitedParallelism(SWEEP_PARALLELISM)

        return coroutineScope {
            addresses.map { address ->
                async(probes) { if (listensForSmb(address)) address else null }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Which addresses to try, from the interface that is actually the LAN.
     *
     * Two things here are worth more than they look.
     *
     * The **whole subnet**, at its real prefix, rather than the /24 around the
     * device. Home networks are mostly /24 and this would have been the same
     * answer — but the handheld this launcher is written for turned out to be on
     * a /23, where scanning the /24 covers half the network and misses a server
     * sitting in the other half with no indication that anything was skipped.
     * Wider than [MAX_SWEEP_HOSTS] it does narrow, because a /16 is sixty-five
     * thousand connection attempts: minutes of scanning, and indistinguishable
     * to a router from a port scan.
     *
     * And it **skips VPN tunnels**, which is not hypothetical: this device is
     * reachable over Tailscale, whose `tun0` carries a perfectly ordinary-looking
     * IPv4 address on a point-to-point interface. Picking the first non-loopback
     * address found would have swept that instead — a /32 with nothing on it —
     * and reported an empty network while the real one sat on `wlan0`
     * untouched. The test is the broadcast address: a real broadcast domain has
     * one, and a point-to-point link does not.
     */
    private fun sweepAddresses(): List<String> = runCatching {
        val candidate = NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter {
                runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint }
                    .getOrDefault(false)
            }
            .flatMap { it.interfaceAddresses }
            .firstOrNull { entry ->
                val address = entry.address
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    entry.broadcast != null
            } ?: return emptyList()

        sweepRange(
            address = (candidate.address as Inet4Address).toLong(),
            prefix = candidate.networkPrefixLength.toInt(),
        )
    }.getOrElse { error ->
        ThorLog.w(TAG, "Could not work out the local subnet: ${error.message}")
        emptyList()
    }

    /**
     * Whether [address] accepts a connection on the SMB port.
     *
     * A connect and an immediate close: enough to know something is serving, and
     * short of anything that would need credentials. A refused connection comes
     * back at once; a host that is not there costs the timeout, which is what
     * sets the length of the whole sweep.
     */
    private fun listensForSmb(address: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, SMB_PORT), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    // ---- What announces itself ---------------------------------------------

    /** Servers advertising `_smb._tcp`, collected for a fixed window. */
    private suspend fun announced(): List<DiscoveredServer> {
        val found = LinkedHashMap<String, DiscoveredServer>()
        withTimeoutOrNull(ANNOUNCE_WINDOW_MS) {
            announcements().collect { found[it.address] = it }
        }
        return found.values.toList()
    }

    private fun announcements(): Flow<DiscoveredServer> = callbackFlow {
        val manager = nsdManager ?: run {
            ThorLog.w(TAG, "No NSD service on this device")
            awaitClose { }
            return@callbackFlow
        }

        /*
         * Resolution is serialised through one in-flight request.
         *
         * `NsdManager.resolveService` fails with `FAILURE_ALREADY_ACTIVE` if a
         * second resolve starts before the first finishes, and a network with
         * several NAS boxes announces them within milliseconds of each other — so
         * resolving on found loses every server after the first.
         */
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = pending.removeFirstOrNull() ?: return
            resolving = true

            @Suppress("DEPRECATION")
            manager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        ThorLog.w(TAG, "Could not resolve ${info.serviceName} ($errorCode)")
                        resolving = false
                        resolveNext()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        info.hostAddress()?.let { address ->
                            trySend(
                                DiscoveredServer(
                                    address = address,
                                    name = info.serviceName?.trim()?.takeIf(String::isNotEmpty),
                                ),
                            )
                        }
                        resolving = false
                        resolveNext()
                    }
                },
            )
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) =
                ThorLog.d(TAG) { "Looking for $serviceType" }

            override fun onServiceFound(info: NsdServiceInfo) {
                // Matched loosely: the same service arrives as `_smb._tcp`,
                // `_smb._tcp.` and `_smb._tcp.local.` depending on the ROM, and an
                // equality test drops every server on the ones that spell it
                // differently. Nothing else can arrive here anyway — this listener
                // is registered for one type.
                if (!info.serviceType.orEmpty().contains(SERVICE_NAME, ignoreCase = true)) return
                pending.addLast(info)
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                ThorLog.w(TAG, "Discovery failed to start ($errorCode)")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        /*
         * Held for as long as discovery runs, and released with it.
         *
         * Wi-Fi hardware filters multicast out of the host's receive path to save
         * power unless something asks it not to, and `NsdManager` does not take
         * this lock for you: without it discovery starts, reports no error, and
         * finds nothing at all — on a network where the NAS is announcing itself
         * perfectly and every other device can see it.
         */
        val multicast = runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock(MULTICAST_TAG)?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()

        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            ThorLog.w(TAG, "Could not start discovery", error)
            close()
        }

        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { multicast?.takeIf { it.isHeld }?.release() }
        }
    }

    // ---- Putting names to addresses ----------------------------------------

    /**
     * Asks each unnamed address what it calls itself, over NetBIOS.
     *
     * A node-status query sent *to the host* rather than the broadcast that
     * NetBIOS discovery normally means — so it survives a network that drops
     * broadcast traffic, which is where the usual approach quietly finds nothing.
     *
     * The whole pass is bounded and its failure is harmless: an address nobody
     * names is still an address that answered on 445, and it is offered as one.
     */
    private suspend fun named(servers: List<DiscoveredServer>): List<DiscoveredServer> {
        val unnamed = servers.filter { it.name == null }
        if (unnamed.isEmpty()) return servers

        val client = runCatching {
            BaseContext(PropertyConfiguration(nameLookupProperties())).nameServiceClient
        }.getOrNull() ?: return servers

        val lookups = ioDispatcher.limitedParallelism(NAME_PARALLELISM)

        val names: Map<String, String> = withTimeoutOrNull(NAME_WINDOW_MS) {
            coroutineScope {
                unnamed.map { server ->
                    async(lookups) {
                        val name = runCatching {
                            client.getNbtAllByAddress(server.address)
                                // 0x20 is the file server service, which is the
                                // name that means "this machine shares files"
                                // rather than the workgroup it belongs to.
                                .firstOrNull { it.nameType == FILE_SERVER_NAME_TYPE }
                                ?.hostName
                        }.getOrNull()
                        server.address to name
                    }
                }.awaitAll()
            }
        }.orEmpty()
            .mapNotNull { (address, name) -> name?.let { address to it } }
            .toMap()

        return servers.map { server ->
            if (server.name != null) server else server.copy(name = names[server.address])
        }
    }

    /** jcifs, configured for one short question rather than for a session. */
    private fun nameLookupProperties() = Properties().apply {
        // One try, briefly. A machine that does not run NetBIOS will never answer,
        // and the default three retries at three seconds each would make naming
        // take longer than the entire scan that found the address.
        setProperty("jcifs.netbios.retryTimeout", NAME_TIMEOUT_MS.toString())
        setProperty("jcifs.netbios.retryCount", "1")
        setProperty("jcifs.smb.client.connTimeout", NAME_TIMEOUT_MS.toString())
    }

    private companion object {
        const val TAG = "SMB"

        const val SMB_PORT = 445

        /** What NAS boxes and macOS file sharing publish. */
        const val SERVICE_TYPE = "_smb._tcp"

        /** Matched loosely against whatever spelling the ROM reports. */
        const val SERVICE_NAME = "smb"

        const val MULTICAST_TAG = "loki-smb-discovery"

        /**
         * Long enough for a NAS to answer, short enough to be a button press.
         *
         * mDNS responders answer within a few hundred milliseconds of the query;
         * this is mostly insurance against one that is slow to wake.
         */
        const val ANNOUNCE_WINDOW_MS = 3_000L

        /**
         * How long a silent host costs.
         *
         * The whole sweep is roughly this multiplied by 254 and divided by the
         * parallelism, so at these values a subnet with nothing on it takes about
         * a second and a half.
         */
        const val PROBE_TIMEOUT_MS = 350
        const val SWEEP_PARALLELISM = 64

        const val NAME_TIMEOUT_MS = 700
        const val NAME_PARALLELISM = 16
        const val NAME_WINDOW_MS = 2_500L

        /** The NetBIOS name a machine registers for file sharing. */
        const val FILE_SERVER_NAME_TYPE = 0x20

        /**
         * The address, across the API change that deprecated the old accessor.
         *
         * `host` is deprecated from API 34 in favour of `hostAddresses`, and the
         * launcher runs on 29 upward — so both are needed.
         */
        fun NsdServiceInfo.hostAddress(): String? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                hostAddresses.firstOrNull()?.hostAddress
            } else {
                @Suppress("DEPRECATION")
                (host as? InetAddress)?.hostAddress
            }
        }.getOrNull()
    }
}

/**
 * Every host address in [address]'s subnet, excluding both ends and itself.
 *
 * Separated from the interface lookup so it can be tested, which matters more
 * than it sounds: this is bit arithmetic over a range whose size is decided at
 * runtime, and getting it subtly wrong produces a scan that finds *some* of the
 * network — the failure that looks exactly like there being nothing there.
 *
 * Narrowed to a /24 when the subnet is larger than [SWEEP_HOST_LIMIT]; see the
 * note on `sweepAddresses`.
 */
internal fun sweepRange(address: Long, prefix: Int): List<String> {
    val clamped = prefix.coerceIn(0, 32)
    val effective = if (hostsIn(clamped) > SWEEP_HOST_LIMIT) NARROWED_PREFIX else clamped
    if (hostsIn(effective) <= 0L) return emptyList()

    val mask = (MASK_ALL shl (BITS - effective)) and MASK_ALL
    val network = address and mask
    val broadcast = network or (mask.inv() and MASK_ALL)

    // Neither end: the network address names the subnet and the broadcast address
    // is not a host. And not this device, which would not be a discovery.
    return ((network + 1) until broadcast)
        .filter { it != address }
        .map(Long::toDottedQuad)
}

/** Addressable hosts in a subnet of this prefix, both ends already excluded. */
private fun hostsIn(prefix: Int): Long =
    if (prefix >= 31) 0L else (1L shl (32 - prefix)) - 2L

/** An IPv4 address as a number, so a subnet is arithmetic rather than four bytes. */
internal fun Inet4Address.toLong(): Long =
    address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }

private fun Long.toDottedQuad(): String =
    "${(this shr 24) and 0xFF}.${(this shr 16) and 0xFF}.${(this shr 8) and 0xFF}.${this and 0xFF}"

private const val BITS = 32
private const val MASK_ALL = 0xFFFFFFFFL

/**
 * Beyond this many hosts, the scan narrows to the device's own /24.
 *
 * A /22 and anything smaller is swept whole, which at the parallelism used is
 * about six seconds at the very worst. A /16 would be minutes, and would look to
 * a router exactly like a port scan.
 */
private const val SWEEP_HOST_LIMIT = 1_024L
private const val NARROWED_PREFIX = 24

/**
 * A file server the network was asked about, before the user has adopted it.
 *
 * Not an [com.thor.core.model.SmbServer]: it has no credentials, no id and
 * nothing stored, and turning one into the other is the user pressing Add. Kept
 * apart so a scan cannot write to settings on its own.
 */
data class DiscoveredServer(
    val address: String,
    /** What it calls itself, when anything would say. */
    val name: String? = null,
) {
    val displayName: String get() = name ?: address

    /** The line under the name, which has to be worth reading when there is one. */
    val detail: String get() = if (name == null) "Answered on port 445" else address
}
