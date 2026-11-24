package com.kiran.vault;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApprovalWorkflowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    static RequestPostProcessor as(String user, String... roles) {
        GrantedAuthority[] authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toArray(GrantedAuthority[]::new);
        return jwt().jwt(j -> j.subject(user)).authorities(authorities);
    }

    String createRecord() throws Exception {
        String body = mvc.perform(post("/api/records").with(as("ingest-svc", "INGEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerRef":"cust-1","ssn":"123-45-6789","dateOfBirth":"1990-04-12",
                                 "email":"jane.doe@example.com","phone":"(408) 555-0199"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ssn").value("***-**-6789"))
                .andExpect(jsonPath("$.email").value("j***@example.com"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    String requestAccess(String recordId, String user, String fields) throws Exception {
        String body = mvc.perform(post("/api/access-requests").with(as(user, "ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recordId\":\"%s\",\"fields\":%s,\"justification\":\"Fraud investigation case FR-2231\"}"
                                .formatted(recordId, fields)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void ssnRequiresDirectorAndGrantIsSingleUse() throws Exception {
        String record = createRecord();
        String req = requestAccess(record, "alice", "[\"SSN\",\"EMAIL\"]");

        // a manager is not senior enough for SSN
        mvc.perform(post("/api/access-requests/" + req + "/approve").with(as("mike", "MANAGER")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/access-requests/" + req + "/approve").with(as("dana", "DIRECTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"approved for FR-2231\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.requiredRole").value("DIRECTOR"));

        // someone else can't redeem alice's grant
        mvc.perform(post("/api/access-requests/" + req + "/reveal").with(as("bob", "ANALYST")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/access-requests/" + req + "/reveal").with(as("alice", "ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.SSN").value("123456789"))
                .andExpect(jsonPath("$.EMAIL").value("jane.doe@example.com"))
                .andExpect(jsonPath("$.PHONE").doesNotExist());

        // single use
        mvc.perform(post("/api/access-requests/" + req + "/reveal").with(as("alice", "ANALYST")))
                .andExpect(status().isConflict());
    }

    @Test
    void confidentialFieldsNeedOnlyAManagerButNeverSelfApproval() throws Exception {
        String record = createRecord();
        String req = requestAccess(record, "carol", "[\"PHONE\"]");

        // carol also holds MANAGER but cannot approve her own request
        mvc.perform(post("/api/access-requests/" + req + "/approve").with(as("carol", "ANALYST", "MANAGER")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/access-requests/" + req + "/approve").with(as("mike", "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredRole").value("MANAGER"));

        mvc.perform(post("/api/access-requests/" + req + "/reveal").with(as("carol", "ANALYST")))
                .andExpect(jsonPath("$.PHONE").value("4085550199"));
    }

    @Test
    void rejectedRequestCannotBeRevealedAndRolesAreEnforced() throws Exception {
        String record = createRecord();
        String req = requestAccess(record, "erin", "[\"DATE_OF_BIRTH\"]");

        mvc.perform(post("/api/access-requests/" + req + "/reject").with(as("mike", "MANAGER")))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/access-requests/" + req + "/reveal").with(as("erin", "ANALYST")))
                .andExpect(status().isConflict());

        // analysts can't ingest, managers can't request, anonymous gets 401
        mvc.perform(post("/api/records").with(as("erin", "ANALYST")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerRef\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/records/" + record)).andExpect(status().isUnauthorized());
    }

    @Test
    void ssnSearchUsesBlindIndexWithoutDecrypting() throws Exception {
        createRecord();
        mvc.perform(post("/api/records/search").with(as("alice", "ANALYST")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ssn\":\"123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ssn").value("***-**-6789"));

        // nothing in the table is plaintext
        Integer plaintextRows = jdbc.queryForObject(
                "select count(*) from pii_record where ssn_cipher like '%6789%' or email_cipher like '%example%'",
                Integer.class);
        assertThat(plaintextRows).isZero();
    }

    @Test
    void auditChainDetectsTampering() throws Exception {
        String record = createRecord();
        requestAccess(record, "alice", "[\"EMAIL\"]");

        mvc.perform(get("/api/admin/audit/verify").with(as("root", "ADMIN")))
                .andExpect(jsonPath("$.valid").value(true));

        jdbc.update("update audit_event set detail = 'nothing to see here' where action = 'ACCESS_REQUESTED'");

        mvc.perform(get("/api/admin/audit/verify").with(as("root", "ADMIN")))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.firstBrokenEventId").isNumber());
    }
}
