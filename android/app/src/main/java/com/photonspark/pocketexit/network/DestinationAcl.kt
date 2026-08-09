package com.photonspark.pocketexit.network

import android.net.Network
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object DestinationAcl {
    fun validateHost(host: String) {
        val normalized = host.trim().trimEnd('.')
        require(normalized.isNotBlank() && normalized.length <= 253) { "Invalid destination host" }
        require(
            !normalized.equals("localhost", ignoreCase = true) &&
                !normalized.endsWith(".localhost", ignoreCase = true),
        ) { "Localhost destinations are blocked" }
    }

    fun resolve(network: Network, host: String, allowPrivate: Boolean): List<InetAddress> {
        validateHost(host)
        val addresses = network.getAllByName(host).toList().distinctBy { it.hostAddress }
        require(addresses.isNotEmpty()) { "Destination did not resolve" }
        if (allowPrivate) return addresses
        val allowed = addresses.filterNot(::isBlocked)
        require(allowed.isNotEmpty()) {
            "Destination resolves only to private, local, multicast, or documentation addresses"
        }
        return allowed
    }

    fun isBlocked(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true

        return when (address) {
            is Inet4Address -> blockedIPv4(address.address)
            is Inet6Address -> blockedIPv6(address.address)
            else -> true
        }
    }

    private fun blockedIPv4(raw: ByteArray): Boolean {
        val a = raw[0].toInt() and 0xff
        val b = raw[1].toInt() and 0xff
        val c = raw[2].toInt() and 0xff
        return when {
            a == 0 -> true
            a == 10 -> true
            a == 100 && b in 64..127 -> true // carrier-grade NAT
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 0 && c == 0 -> true
            a == 192 && b == 0 && c == 2 -> true
            a == 192 && b == 168 -> true
            a == 198 && b in 18..19 -> true
            a == 198 && b == 51 && c == 100 -> true
            a == 203 && b == 0 && c == 113 -> true
            a >= 224 -> true
            else -> false
        }
    }

    private fun blockedIPv6(raw: ByteArray): Boolean {
        val ipv4Compatible = raw.take(12).all { it.toInt() == 0 }
        val ipv4Mapped = raw.take(10).all { it.toInt() == 0 } &&
            (raw[10].toInt() and 0xff) == 0xff &&
            (raw[11].toInt() and 0xff) == 0xff
        if (ipv4Compatible || ipv4Mapped) {
            return blockedIPv4(raw.copyOfRange(12, 16))
        }

        val first = raw[0].toInt() and 0xff
        val second = raw[1].toInt() and 0xff
        val documentation =
            first == 0x20 && second == 0x01 &&
                (raw[2].toInt() and 0xff) == 0x0d &&
                (raw[3].toInt() and 0xff) == 0xb8
        val uniqueLocal = first and 0xfe == 0xfc
        val linkLocal = first == 0xfe && second and 0xc0 == 0x80
        val multicast = first == 0xff
        return documentation || uniqueLocal || linkLocal || multicast
    }
}
