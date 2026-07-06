package com.local.splprint

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object PrinterSender {

    private const val PORT = 9100
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000

    /**
     * Opens a raw TCP socket to [ip]:9100, writes [jobBytes], flushes, and
     * closes. Must be called from a background thread (blocking I/O).
     * Throws IOException on any failure.
     */
    @Throws(IOException::class)
    fun send(ip: String, jobBytes: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().use { out ->
                out.write(jobBytes)
                out.flush()
            }
        }
    }
}
