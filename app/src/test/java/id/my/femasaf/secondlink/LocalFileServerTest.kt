package id.my.femasaf.secondlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileServerTest {
    @Test fun acceptsPrivateAndLoopbackAddresses() {
        assertTrue(LocalFileServer.isPrivateOrLoopback("127.0.0.1"))
        assertTrue(LocalFileServer.isPrivateOrLoopback("10.2.3.4"))
        assertTrue(LocalFileServer.isPrivateOrLoopback("172.16.0.1"))
        assertTrue(LocalFileServer.isPrivateOrLoopback("172.31.255.1"))
        assertTrue(LocalFileServer.isPrivateOrLoopback("192.168.1.20"))
    }

    @Test fun rejectsPublicAndOutOfRangeAddresses() {
        assertFalse(LocalFileServer.isPrivateOrLoopback("8.8.8.8"))
        assertFalse(LocalFileServer.isPrivateOrLoopback("172.15.0.1"))
        assertFalse(LocalFileServer.isPrivateOrLoopback("172.32.0.1"))
    }
}
