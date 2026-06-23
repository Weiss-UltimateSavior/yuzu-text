package com.yuzugame.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CryptoUtils 单元测试 —— 验证 AES-GCM 加解密的正确性和边界情况。
 */
class CryptoUtilsTest {

    @Test
    void encryptAndDecrypt_shouldRoundTrip() {
        String key = CryptoUtils.generateKey();
        String plaintext = "sk-test-api-key-12345";
        String encrypted = CryptoUtils.encrypt(plaintext, key);
        assertNotNull(encrypted);
        assertNotEquals(plaintext, encrypted);
        String decrypted = CryptoUtils.decrypt(encrypted, key);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void encryptWithPrefix_shouldAddEncPrefix() {
        String key = CryptoUtils.generateKey();
        String plaintext = "secret-data";
        String encrypted = CryptoUtils.encryptWithPrefix(plaintext, key);
        assertNotNull(encrypted);
        assertTrue(CryptoUtils.isEncrypted(encrypted));
    }

    @Test
    void decryptWithPrefix_shouldHandleNonEncryptedValue() {
        String key = CryptoUtils.generateKey();
        String plain = "not-encrypted";
        // 非加密格式应原样返回
        assertEquals(plain, CryptoUtils.decryptWithPrefix(plain, key));
    }

    @Test
    void decryptWithPrefix_shouldDecryptEncryptedValue() {
        String key = CryptoUtils.generateKey();
        String plaintext = "sensitive-info";
        String encrypted = CryptoUtils.encryptWithPrefix(plaintext, key);
        String decrypted = CryptoUtils.decryptWithPrefix(encrypted, key);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void encrypt_shouldReturnNullForNullInput() {
        assertNull(CryptoUtils.encrypt(null, CryptoUtils.generateKey()));
    }

    @Test
    void encrypt_shouldReturnEmptyForEmptyInput() {
        assertEquals("", CryptoUtils.encrypt("", CryptoUtils.generateKey()));
    }

    @Test
    void decrypt_withWrongKey_shouldReturnNull() {
        String key1 = CryptoUtils.generateKey();
        String key2 = CryptoUtils.generateKey();
        String encrypted = CryptoUtils.encrypt("test", key1);
        // 用错误的密钥解密应失败
        assertNull(CryptoUtils.decrypt(encrypted, key2));
    }

    @Test
    void generateKey_shouldReturnBase64Encoded32Bytes() {
        String key = CryptoUtils.generateKey();
        byte[] decoded = java.util.Base64.getDecoder().decode(key);
        assertEquals(32, decoded.length); // AES-256 需要 32 字节密钥
    }

    @Test
    void isEncrypted_shouldCheckPrefix() {
        assertTrue(CryptoUtils.isEncrypted("ENC:abc123"));
        assertFalse(CryptoUtils.isEncrypted("abc123"));
        assertFalse(CryptoUtils.isEncrypted(null));
    }

    @Test
    void encrypt_differentEncryptionsShouldDifferDueToRandomIv() {
        String key = CryptoUtils.generateKey();
        String plaintext = "same-text";
        String enc1 = CryptoUtils.encrypt(plaintext, key);
        String enc2 = CryptoUtils.encrypt(plaintext, key);
        assertNotEquals(enc1, enc2); // IV 不同，密文应不同
    }
}
