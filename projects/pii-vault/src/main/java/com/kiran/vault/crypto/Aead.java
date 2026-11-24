package com.kiran.vault.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * AES-256-GCM authenticated encryption. Output layout: {@code 12-byte IV || ciphertext+tag}.
 * The associated data binds a ciphertext to its context (record id + field), so a ciphertext copied into another
 * row or column fails to decrypt instead of silently revealing data.
 */
public final class Aead {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Aead() {
    }

    public static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Encryption failed", e);
        }
    }

    public static byte[] decrypt(SecretKey key, byte[] blob, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            c.updateAAD(aad);
            return c.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Decryption failed (wrong key, wrong context, or tampered ciphertext)", e);
        }
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
