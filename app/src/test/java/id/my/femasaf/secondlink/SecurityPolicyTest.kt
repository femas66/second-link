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
    fun accessCodeRequiresLengthLettersAndDigits() {
        assertFalse(SecurityPolicy.isStrongAccessCode("short1"))
        assertFalse(SecurityPolicy.isStrongAccessCode("onlyletters"))
        assertFalse(SecurityPolicy.isStrongAccessCode("12345678"))
        assertTrue(SecurityPolicy.isStrongAccessCode("SecondLink2026"))
    }

    @Test
    fun auditKeepsOnlyCommandName() {
        assertEquals("/camera", SecurityPolicy.redactCommand("/camera f #VerySecret9"))
        assertEquals("/download", SecurityPolicy.redactCommand("/download https://example.com/private.pdf #Code1234"))
    }
}
