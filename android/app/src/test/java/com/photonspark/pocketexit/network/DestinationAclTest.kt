package com.photonspark.pocketexit.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class DestinationAclTest {
    @Test
    fun blocksPrivateAndDocumentationRanges() {
        assertTrue(DestinationAcl.isBlocked(InetAddress.getByName("127.0.0.1")))
        assertTrue(DestinationAcl.isBlocked(InetAddress.getByName("10.1.2.3")))
        assertTrue(DestinationAcl.isBlocked(InetAddress.getByName("100.64.1.2")))
        assertTrue(DestinationAcl.isBlocked(InetAddress.getByName("203.0.113.9")))
        assertTrue(DestinationAcl.isBlocked(InetAddress.getByName("2001:db8::1")))
        assertTrue(DestinationAcl.isBlocked(InetAddress.getByName("fd00::1")))
        val mappedPrivate = ByteArray(16).also {
            it[10] = 0xff.toByte()
            it[11] = 0xff.toByte()
            it[12] = 10
            it[13] = 1
            it[14] = 2
            it[15] = 3
        }
        assertTrue(DestinationAcl.isBlocked(Inet6Address.getByAddress(null, mappedPrivate, -1)))
    }

    @Test
    fun allowsPublicAddresses() {
        assertFalse(DestinationAcl.isBlocked(InetAddress.getByName("1.1.1.1")))
        assertFalse(DestinationAcl.isBlocked(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
