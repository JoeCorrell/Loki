package com.thor.data.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Which addresses a network scan actually tries.
 *
 * Bit arithmetic over a range whose size is decided at runtime, and the reason it
 * is tested separately from the thing that calls it is that getting it subtly
 * wrong does not throw — it scans *some* of the network, which is
 * indistinguishable from there being nothing on the rest of it.
 *
 * The /23 case is not hypothetical. The handheld this launcher is written for
 * turned out to be on one, and an earlier version of this scanned only the /24
 * around the device — covering half the network and reporting the other half as
 * empty.
 */
class SweepRangeTest {

    private fun ip(text: String): Long = (InetAddress.getByName(text) as Inet4Address).toLong()

    // ---- The ordinary case --------------------------------------------------

    @Test
    fun `a 24 covers every host but the two ends`() {
        val range = sweepRange(ip("192.168.1.50"), prefix = 24)

        assertThat(range).hasSize(253) // 254 hosts, less this device
        assertThat(range).contains("192.168.1.1")
        assertThat(range).contains("192.168.1.254")
    }

    /**
     * The network and broadcast addresses are not hosts, and this device is not a
     * discovery.
     */
    @Test
    fun `neither end of the subnet nor the device itself is probed`() {
        val range = sweepRange(ip("192.168.1.50"), prefix = 24)

        assertThat(range).doesNotContain("192.168.1.0")
        assertThat(range).doesNotContain("192.168.1.255")
        assertThat(range).doesNotContain("192.168.1.50")
    }

    /** An address above 127 makes the top bit set; the arithmetic must not sign-extend. */
    @Test
    fun `a high address range is not mangled by the sign bit`() {
        val range = sweepRange(ip("192.168.1.50"), prefix = 24)

        assertThat(range.first()).isEqualTo("192.168.1.1")
        assertThat(range.last()).isEqualTo("192.168.1.254")
    }

    // ---- Wider than a 24 ----------------------------------------------------

    /**
     * The case that was silently half-scanned: a /23 spans two /24s, and a server
     * in the upper half is as real as one in the lower.
     */
    @Test
    fun `a 23 covers both halves of the network`() {
        val range = sweepRange(ip("10.65.232.48"), prefix = 23)

        assertThat(range).hasSize(509) // 510 hosts, less this device
        assertThat(range).contains("10.65.232.1")
        assertThat(range).contains("10.65.233.254")
        assertThat(range).doesNotContain("10.65.233.255")
    }

    @Test
    fun `a 22 is still swept whole`() {
        assertThat(sweepRange(ip("10.0.4.7"), prefix = 22)).hasSize(1021)
    }

    // ---- Too wide to sweep --------------------------------------------------

    /**
     * A /16 is sixty-five thousand connection attempts — minutes of scanning, and
     * indistinguishable to a router from a port scan. It narrows to the /24 the
     * device is on, which is the useful part of it.
     */
    @Test
    fun `a 16 narrows to the device's own 24`() {
        val range = sweepRange(ip("10.4.7.9"), prefix = 16)

        assertThat(range).hasSize(253)
        assertThat(range).contains("10.4.7.1")
        assertThat(range).contains("10.4.7.254")
        assertThat(range).doesNotContain("10.4.8.1")
    }

    @Test
    fun `an 8 narrows the same way`() {
        assertThat(sweepRange(ip("10.4.7.9"), prefix = 8)).hasSize(253)
    }

    // ---- Nothing to scan ----------------------------------------------------

    /**
     * A point-to-point link has no other hosts on it. The interface filter should
     * already have rejected one, and this is the second line.
     */
    @Test
    fun `a 32 has nothing to probe`() {
        assertThat(sweepRange(ip("100.83.63.70"), prefix = 32)).isEmpty()
    }

    @Test
    fun `a 31 has nothing to probe either`() {
        assertThat(sweepRange(ip("192.168.1.4"), prefix = 31)).isEmpty()
    }

    /** A nonsense prefix from a driver must not produce a nonsense range. */
    @Test
    fun `an out of range prefix is clamped rather than trusted`() {
        assertThat(sweepRange(ip("192.168.1.50"), prefix = -5)).hasSize(253)
        assertThat(sweepRange(ip("192.168.1.50"), prefix = 99)).isEmpty()
    }
}
