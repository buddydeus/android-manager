package com.buddydeus.androidmanager.data
import org.junit.Assert.*
import org.junit.Test

class PasswordCodecTest {
    @Test fun correctPinVerifiesAndWrongPinFails() {
        val text=PasswordCodec.create("123456".toCharArray())
        assertTrue(PasswordCodec.verify(text,"123456".toCharArray()))
        assertFalse(PasswordCodec.verify(text,"654321".toCharArray()))
        assertFalse(text.contains("123456"))
    }
    @Test(expected=Exception::class) fun arbitraryWorkFactorRejected() {
        PasswordCodec.validate("{\"schemaVersion\":1,\"kdf\":\"PBKDF2WithHmacSHA256\",\"iterations\":2147483647}")
    }
    @Test(expected=Exception::class) fun shortPinRejected() { PasswordCodec.create("0000".toCharArray()) }
    @Test fun randomSaltChangesSamePinDocument() { assertNotEquals(PasswordCodec.create("123456".toCharArray()),PasswordCodec.create("123456".toCharArray())) }
}
