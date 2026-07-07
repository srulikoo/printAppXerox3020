package com.local.splprint

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object PrinterSender {

    private const val PORT = 9100
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000
    private const val CHECK_TIMEOUT_MS = 3000

    /**
     * Quick, non-destructive reachability test: opens a TCP connection to
     * [ip]:9100 and immediately closes it without sending any data. Returns
     * true if the printer accepted the connection within [timeoutMs].
     * Must be called from a background thread (blocking I/O).
     */
    fun checkConnection(ip: String, timeoutMs: Int = CHECK_TIMEOUT_MS): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, PORT), timeoutMs)
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Opens a raw TCP socket to [ip]:9100, writes [jobBytes], flushes, and
     * closes. Must be called from a background thread (blocking I/O).
     * Throws IOException on any failure.
     *
     * Important: write()/flush() only guarantee the data reached this
     * device's own OS send buffer -- not that it was actually transmitted
     * over Wi-Fi or received by the printer. Closing the socket immediately
     * after can send a TCP reset before an embedded printer's (often slow)
     * network stack has fully drained the data, silently truncating the
     * job with no error on either side. We explicitly half-close the
     * connection (shutdownOutput, a proper "no more data" signal) and give
     * the network stack a brief moment to finish draining before the final
     * close, instead of closing abruptly right after flush().
     */
    @Throws(IOException::class)
    fun send(ip: String, jobBytes: ByteArray) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val out = socket.getOutputStream()
            out.write(jobBytes)
            out.flush()

            // Proper half-close: signals "no more data" cleanly (TCP FIN)
            // rather than abruptly resetting the connection.
            socket.shutdownOutput()

            // Give the printer's network stack time to finish receiving
            // and processing before we fully close the socket.
            Thread.sleep(1000)
        } finally {
            socket.close()
        }
    }
}
