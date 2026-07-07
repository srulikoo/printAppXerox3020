package com.local.splprint

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkScanner {

    private const val SCAN_TIMEOUT_MS = 300
    private const val MAX_CONCURRENT = 48

    /**
     * Returns this device's own IPv4 address on its active network interface
     * (e.g. Wi-Fi), or null if none could be found. No special permission is
     * required to enumerate interfaces this way.
     */
    fun getLocalIPv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            // fall through, return null below
        }
        return null
    }

    /** "192.168.1.43" -> "192.168.1" */
    fun subnetPrefixOf(ipv4: String): String? {
        val parts = ipv4.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    /**
     * Sweeps [prefix].1 through [prefix].254 for hosts accepting a TCP
     * connection on port 9100 (JetDirect/raw printing), with limited
     * concurrency so it doesn't open 254 sockets at once. Returns the list
     * of responding IPs, sorted numerically by last octet.
     */
    suspend fun scanForPrinters(prefix: String): List<String> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT)
        val deferredResults = (1..254).map { host ->
            async {
                val ip = "$prefix.$host"
                semaphore.withPermit {
                    if (PrinterSender.checkConnection(ip, SCAN_TIMEOUT_MS)) ip else null
                }
            }
        }
        deferredResults.mapNotNull { it.await() }
    }
}
