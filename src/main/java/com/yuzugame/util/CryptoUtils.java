package com.yuzugame.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加密工具 —— 用于加密存储敏感字段（如用户自定义 LLM API Key）。
 *
 * <p>使用 AES-256-GCM 认证加密，每次加密生成随机 12 字节 IV，密文格式：Base64(IV + ciphertext + tag)。</p>
 */
public final class CryptoUtils {

    private static final Logger log = LoggerFactory.getLogger(CryptoUtils.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtils() {}

    /**
     * 加密明文。
     *
     * @param plaintext 明文
     * @param key       Base64 编码的 256 位密钥
     * @return Base64 编码的密文（含 IV），加密失败返回 null
     */
    public static String encrypt(String plaintext, String key) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            SecretKeySpec secretKey = decodeKey(key);
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解密密文。
     *
     * @param ciphertext Base64 编码的密文（含 IV）
     * @param key        Base64 编码的 256 位密钥
     * @return 明文，解密失败返回 null
     */
    public static String decrypt(String ciphertext, String key) {
        if (ciphertext == null || ciphertext.isEmpty()) return ciphertext;
        try {
            SecretKeySpec secretKey = decodeKey(key);
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成随机 AES-256 密钥（Base64 编码）。
     * 在应用启动时调用一次，将结果配置到环境变量 FIELD_ENCRYPTION_KEY 中。
     */
    public static String generateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 判断字符串是否为加密后的密文（以 "ENC:" 前缀标识）。
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith("ENC:");
    }

    /**
     * 加密并添加前缀标识。
     */
    public static String encryptWithPrefix(String plaintext, String key) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        String encrypted = encrypt(plaintext, key);
        return encrypted != null ? "ENC:" + encrypted : null;
    }

    /**
     * 解密带前缀标识的密文。如果不是加密格式，原样返回（兼容旧数据）。
     */
    public static String decryptWithPrefix(String ciphertext, String key) {
        if (ciphertext == null || ciphertext.isEmpty()) return ciphertext;
        if (!isEncrypted(ciphertext)) return ciphertext;
        return decrypt(ciphertext.substring(4), key);
    }

    private static SecretKeySpec decodeKey(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        return new SecretKeySpec(keyBytes, "AES");
    }
}
