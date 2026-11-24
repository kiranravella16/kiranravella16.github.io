package com.kiran.vault;

import com.jayway.jsonpath.JsonPath;
import com.kiran.vault.record.PiiField;
import com.kiran.vault.record.PiiRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs with KEK v2 active and exercises real HS256 tokens from /dev/token end to end.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rotation;DB_CLOSE_DELAY=-1",
        "pii.keys.active-kek-version=2",
        "pii.dev-tokens.enabled=true"})
@AutoConfigureMockMvc
class KeyRotationAndTokenTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    PiiRecordService records;

    String token(String subject, String... roles) throws Exception {
        String rolesJson = String.join("\",\"", roles);
        String body = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"%s\",\"roles\":[\"%s\"]}".formatted(subject, rolesJson)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.access_token");
    }

    @Autowired
    com.kiran.vault.crypto.KeyProperties keys;
    @Autowired
    com.kiran.vault.record.PiiRecordRepository repo;
    @Autowired
    com.kiran.vault.audit.AuditService audit;

    @Test
    void rewrapMovesOldRecordsToActiveKekAndDataStaysReadable() throws Exception {
        // a "legacy" writer still on KEK v1, sharing the same database
        var v1Crypto = new com.kiran.vault.crypto.EnvelopeCrypto(
                new com.kiran.vault.crypto.KeyProperties(keys.kek(), 1, keys.blindIndexKey()));
        var legacyWriter = new PiiRecordService(repo, v1Crypto, audit);
        var legacy = legacyWriter.store(new PiiRecordService.NewRecord("c-9", "987654321", null, null, null), "legacy");
        assertThat(legacy.kekVersion()).isEqualTo(1);

        mvc.perform(post("/api/admin/kek/rewrap").header("Authorization", token("root", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewrapped").value(1));

        assertThat(records.masked(legacy.id()).kekVersion()).isEqualTo(2);
        assertThat(records.decrypt(legacy.id(), Set.of(PiiField.SSN))).containsEntry(PiiField.SSN, "987654321");
        // second run is a no-op
        mvc.perform(post("/api/admin/kek/rewrap").header("Authorization", token("root", "ADMIN")))
                .andExpect(jsonPath("$.rewrapped").value(0));
    }

    @Test
    void realTokensAreValidatedAndRolesMapped() throws Exception {
        String ingest = token("ingest-svc", "INGEST");
        String body = mvc.perform(post("/api/records").header("Authorization", ingest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerRef\":\"c-10\",\"email\":\"sam@example.org\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/records/" + id).header("Authorization", token("viewer", "ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("s***@example.org"));

        mvc.perform(get("/api/records/" + id).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
