package com.sirrlevi.guardian.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.sirrlevi.guardian.core.DomainRules
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class DomainFilterVpnService : VpnService() {
    @Volatile private var active = true
    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        active = true
        // Remembered so the filter can be restarted after an in-place app update.
        getSharedPreferences("guardian_child", MODE_PRIVATE).edit().putBoolean("vpn_wanted", true).apply()
        startForeground(NOTIFICATION_ID, notification())
        if (tunnel == null) establishTunnel()
        return START_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Guardian domain filtering", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Visible status while family-approved DNS filtering is active." }
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Guardian protection active")
                .setContentText("Family-approved domain filtering is running on this device.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Guardian protection active")
                .setContentText("Family-approved domain filtering is running.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
        }
    }

    private fun establishTunnel() {
        try {
            tunnel = Builder()
                .setSession("Guardian DNS filter")
                .setMtu(1500)
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32)
                .establish()
            val descriptor = tunnel ?: return
            thread(name = "guardian-dns-tunnel") {
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        val buffer = ByteArray(32767)
                        while (active) {
                            val count = try { input.read(buffer) } catch (_: Exception) { break }
                            if (count <= 0) continue
                            try {
                                val reply = handlePacket(buffer.copyOf(count))
                                if (reply != null) output.write(reply)
                            } catch (_: Exception) {
                                // Malformed or unsupported packets are left untouched.
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun handlePacket(packet: ByteArray): ByteArray? {
        if (packet.size < 28) return null
        if ((packet[0].toInt() ushr 4 and 0x0f) != 4) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < 20 || packet.size < ipHeaderLength + 8) return null
        if ((packet[9].toInt() and 0xff) != 17) return null
        val udpOffset = ipHeaderLength
        val destinationPort = readU16(packet, udpOffset + 2)
        if (destinationPort != 53) return null
        val dnsOffset = udpOffset + 8
        val declaredUdpLength = readU16(packet, udpOffset + 4)
        val dnsLength = minOf(declaredUdpLength - 8, packet.size - dnsOffset)
        if (dnsLength < 12) return null
        val query = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val question = readQuestion(query) ?: return null
        val rules = blockedDomains()
        val blocked = DomainRules.isBlockedHost(question.first, rules)
        val answer = if (blocked) blockedResponse(query, question.second) else forwardDns(query) ?: return null
        return ipv4UdpReply(packet, ipHeaderLength, udpOffset, answer)
    }

    private fun blockedDomains(): List<String> = try {
        val prefs = getSharedPreferences("guardian_child", MODE_PRIVATE)
        Gson().fromJson(prefs.getString("blocked_domains", "[]"), Array<String>::class.java)?.toList().orEmpty()
    } catch (_: Exception) { emptyList() }

    private fun readQuestion(dns: ByteArray): Pair<String, Int>? {
        var index = 12
        val labels = ArrayList<String>()
        while (index < dns.size) {
            val size = dns[index++].toInt() and 0xff
            if (size == 0) break
            if (size > 63 || index + size > dns.size) return null
            labels.add(String(dns, index, size, Charsets.US_ASCII))
            index += size
        }
        if (labels.isEmpty() || index + 4 > dns.size) return null
        return labels.joinToString(".") to (index + 4)
    }

    private fun blockedResponse(query: ByteArray, questionEnd: Int): ByteArray {
        val response = query.copyOf(questionEnd)
        response[2] = 0x81.toByte()
        response[3] = 0x83.toByte() // NXDOMAIN
        for (offset in listOf(6, 8, 10)) {
            response[offset] = 0
            response[offset + 1] = 0
        }
        return response
    }

    private fun forwardDns(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                socket.soTimeout = 2500
                val server = InetAddress.getByName("1.1.1.1")
                socket.send(DatagramPacket(query, query.size, server, 53))
                val buffer = ByteArray(4096)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                buffer.copyOf(reply.length)
            }
        } catch (_: Exception) { null }
    }

    private fun ipv4UdpReply(original: ByteArray, ipLength: Int, udpOffset: Int, dns: ByteArray): ByteArray {
        val sourcePort = readU16(original, udpOffset)
        val destinationPort = readU16(original, udpOffset + 2)
        val reply = ByteArray(20 + 8 + dns.size)
        reply[0] = 0x45
        writeU16(reply, 2, reply.size)
        reply[8] = 64
        reply[9] = 17
        System.arraycopy(original, 16, reply, 12, 4)
        System.arraycopy(original, 12, reply, 16, 4)
        writeU16(reply, 10, checksum(reply, 0, 20))
        writeU16(reply, 20, destinationPort)
        writeU16(reply, 22, sourcePort)
        writeU16(reply, 24, 8 + dns.size)
        // A zero UDP checksum is valid for IPv4.
        System.arraycopy(dns, 0, reply, 28, dns.size)
        return reply
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            val high = data[i].toInt() and 0xff
            val low = if (i + 1 < offset + length) data[i + 1].toInt() and 0xff else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    override fun onRevoke() {
        active = false
        getSharedPreferences("guardian_child", MODE_PRIVATE).edit().putBoolean("vpn_wanted", false).apply()
        tunnel?.close()
        tunnel = null
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        active = false
        tunnel?.close()
        tunnel = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "guardian_domain_filter"
        private const val NOTIFICATION_ID = 7124
        private const val VPN_ADDRESS = "10.77.0.1"
        private const val DNS_ADDRESS = "10.77.0.2"
    }
}
