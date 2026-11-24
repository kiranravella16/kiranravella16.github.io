package com.kiran.vault.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Key material, base64-encoded 256-bit keys. In production these come from Cloud KMS / an HSM or a secrets manager
 * (Akeyless, Vault), never from a file in the repo.
 *
 * @param kek          key-encryption keys by version. Add a version and bump {@code activeKekVersion} to rotate.
 * @param activeKekVersion version used to wrap new data keys
 * @param blindIndexKey HMAC key for searchable blind indexes (kept separate from the KEKs)
 */
@ConfigurationProperties(prefix = "pii.keys")
public record KeyProperties(Map<Integer, String> kek, int activeKekVersion, String blindIndexKey) {
}
