package com.syed.apiqa.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @Test
    void shouldEncryptAndDecryptSuccessfully() {
        String sensitiveToken = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret-token-payload-xyz";
        String encrypted = converter.convertToDatabaseColumn(sensitiveToken);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("ENC:"), "Encrypted data must start with ENC: prefix");
        assertNotEquals(sensitiveToken, encrypted, "Plaintext must not appear in database column");

        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertEquals(sensitiveToken, decrypted, "Decrypted attribute must match original plaintext");
    }

    @Test
    void shouldHandleNullAndEmpty() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertEquals("", converter.convertToDatabaseColumn(""));
        assertEquals("", converter.convertToEntityAttribute(""));
    }

    @Test
    void shouldHandleLegacyUnencryptedData() {
        String legacyPlaintext = "raw-legacy-token-12345";
        // When reading legacy data that was not encrypted
        String readValue = converter.convertToEntityAttribute(legacyPlaintext);
        assertEquals(legacyPlaintext, readValue, "Legacy unencrypted values must be read gracefully");
    }

    @Test
    void shouldFailOnTamperedCiphertext() {
        String sensitiveToken = "super-secret-password-123";
        String encrypted = converter.convertToDatabaseColumn(sensitiveToken);

        // Tamper with the ciphertext
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute(tampered));
    }
}
