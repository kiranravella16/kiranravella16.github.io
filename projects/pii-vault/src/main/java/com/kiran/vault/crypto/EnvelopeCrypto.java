package com.kiran.vault.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Two-layer envelope encryption:
 * <ol>
 *     <li><b>Layer 1:</b> every record gets its own random 256-bit data key (DEK); each PII field is encrypted with
 *         the DEK using AES-GCM, with {@code recordId:field} as associated data.</li>
 *     <li><b>Layer 2:</b> the DEK is wrapped by a versioned key-encryption key (KEK). Only the wrapped DEK is
 *         stored. Rotating the KEK means re-wrapping DEKs, not re-encrypting every field.</li>
 * </ol>
 * A keyed HMAC "blind index" lets you look a record up by SSN without decrypting anything.
 */
@Component
public class EnvelopeCrypto {

    private final Map<Integer, SecretKey> keks = new TreeMap<>();
    private final int activeVersion;
    private final SecretKey indexKey;

    public EnvelopeCrypto(KeyProperties props) {
        props.kek().forEach((version, b64) -> keks.put(version, aesKey(b64)));
        if (!keks.containsKey(props.activeKekVersion())) {
            throw new IllegalStateException("Active KEK version " + props.activeKekVersion() + " is not configured");
        }
        this.activeVersion = props.activeKekVersion();
        this.indexKey = new SecretKeySpec(Base64.getDecoder().decode(props.blindIndexKey()), "HmacSHA256");
    }

    public record WrappedKey(int kekVersion, byte[] wrapped) {
    }

    public int activeKekVersion() {
        return activeVersion;
    }

    public SecretKey newDataKey() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            return gen.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public WrappedKey wrap(SecretKey dek, String recordId) {
        byte[] wrapped = Aead.encrypt(keks.get(activeVersion), dek.getEncoded(), dekAad(recordId, activeVersion));
        return new WrappedKey(activeVersion, wrapped);
    }

    public SecretKey unwrap(WrappedKey wk, String recordId) {
        SecretKey kek = keks.get(wk.kekVersion());
        if (kek == null) {
            throw new IllegalStateException("KEK version " + wk.kekVersion() + " is no longer available");
        }
        return new SecretKeySpec(Aead.decrypt(kek, wk.wrapped(), dekAad(recordId, wk.kekVersion())), "AES");
    }

    /** Re-wraps a DEK under the active KEK. Field ciphertexts are untouched. */
    public WrappedKey rewrap(WrappedKey wk, String recordId) {
        return wrap(unwrap(wk, recordId), recordId);
    }

    public String encryptField(SecretKey dek, String recordId, String field, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] blob = Aead.encrypt(dek, plaintext.getBytes(StandardCharsets.UTF_8), fieldAad(recordId, field));
        return Base64.getEncoder().encodeToString(blob);
    }

    public String decryptField(SecretKey dek, String recordId, String field, String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        byte[] pt = Aead.decrypt(dek, Base64.getDecoder().decode(ciphertext), fieldAad(recordId, field));
        return new String(pt, StandardCharsets.UTF_8);
    }

    /** Deterministic keyed hash of a normalized value, for equality search on encrypted data. */
    public String blindIndex(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indexKey);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fieldAad(String recordId, String field) {
        return (recordId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] dekAad(String recordId, int version) {
        return ("dek:" + recordId + ":v" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKey aesKey(String b64) {
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length != 32) {
            throw new IllegalStateException("KEKs must be 256-bit (32 bytes), got " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }
}
