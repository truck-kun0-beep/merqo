package com.merqo.integration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * AES-GCM authenticated encryption utility for external integration webhook secrets.
 *
 * <p>Responsibility: encrypt and decrypt integration client HMAC secrets using the master key from
 * {@code INTEGRATION_SECRET_KEY}, with fresh 96-bit random IVs and 128-bit authentication tags.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final IntegrationProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretCipher(IntegrationProperties properties) {
        this.properties = properties;
    }

    /**
     * Encrypts a plaintext integration secret into a Base64-encoded IV + ciphertext blob.
     *
     * @param plaintext raw client secret
     * @return encrypted value safe for database storage
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Secret plaintext must not be blank.");
        }
        SecretKey key = deriveKey();
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined =
                    ByteBuffer.allocate(iv.length + encrypted.length)
                            .put(iv)
                            .put(encrypted)
                            .array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt integration secret.", ex);
        }
    }

    /**
     * Decrypts a stored {@code secret_encrypted} blob back into the plaintext secret for HMAC
     * verification. Never logged or serialized.
     *
     * @param encrypted Base64-encoded IV + ciphertext
     * @return plaintext client secret
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            throw new IllegalStateException("Encrypted integration secret is missing.");
        }
        SecretKey key = deriveKey();
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("Malformed encrypted integration secret.");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] cipherBytes = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(combined, IV_LENGTH_BYTES, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "Failed to decrypt integration secret. Check INTEGRATION_SECRET_KEY.", ex);
        }
    }

    private SecretKey deriveKey() {
        if (!properties.hasSecretKey()) {
            throw new IllegalStateException(
                    "INTEGRATION_SECRET_KEY environment variable must be configured.");
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(properties.getSecretKey().getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to derive AES key", ex);
        }
    }
}
