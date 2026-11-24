package com.kiran.pki;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CertificateApiTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pki.keystore-path", () -> {
            try {
                return Files.createTempDirectory("ca-api").resolve("ca.p12").toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:api;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void issueThenFetchSummaryAndCrl() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commonName":"api.internal","dnsNames":["api.internal"],"validityDays":10,"autoRenew":true,"owner":"platform"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificate.status").value("ACTIVE"))
                .andExpect(jsonPath("$.privateKeyPem", containsString("PRIVATE KEY")));

        mvc.perform(get("/api/certificates/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiringSoon").value(1));

        mvc.perform(get("/api/ca/crl.pem"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("X509 CRL")));
    }

    @Test
    void rejectsInvalidCommonName() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commonName\":\"bad name; rm -rf\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCertificateIs404() throws Exception {
        mvc.perform(get("/api/certificates/99999")).andExpect(status().isNotFound());
    }
}
