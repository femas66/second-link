package id.my.femasaf.secondlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyTest {
    @Test
    fun credentialsRejectEmptyAndMalformedValues() {
        assertFalse(SecurityPolicy.isValidToken(""))
        assertFalse(SecurityPolicy.isValidToken("123:secret"))
        assertFalse(SecurityPolicy.isValidChatId(""))
        assertFalse(SecurityPolicy.isValidChatId("0"))
        assertFalse(SecurityPolicy.isValidChatId("not-a-chat"))
    }

    @Test
    fun credentialsAcceptExpectedTelegramShapes() {
        assertTrue(SecurityPolicy.isValidToken("123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi"))
        assertTrue(SecurityPolicy.isValidChatId("5562277502"))
        assertTrue(SecurityPolicy.isValidChatId("-1001234567890"))
    }

    @Test
    fun webServerPasswordValidation() {
        assertFalse(SecurityPolicy.isValidWebServerPassword(""))
        assertFalse(SecurityPolicy.isValidWebServerPassword("123"))
        assertTrue(SecurityPolicy.isValidWebServerPassword("1234"))
        assertTrue(SecurityPolicy.isValidWebServerPassword("admin123"))
        assertTrue(SecurityPolicy.isValidWebServerPassword("SecondLink2026"))
    }

    @Test
    fun auditKeepsOnlyCommandName() {
        assertEquals("/camera", SecurityPolicy.redactCommand("/camera f #VerySecret9"))
        assertEquals("/download", SecurityPolicy.redactCommand("/download https://example.com/private.pdf #Code1234"))
    }
}
