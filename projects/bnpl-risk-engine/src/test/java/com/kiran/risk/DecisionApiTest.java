package com.kiran.risk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DecisionApiTest {

    @Autowired
    MockMvc mvc;

    static String body(String appId, String customer, String product, int amount) {
        return """
                {"applicationId":"%s","customerId":"%s","product":"%s","amount":%d,"termMonths":12,
                 "monthlyIncome":4800,"state":"CA"}
                """.formatted(appId, customer, product, amount);
    }

    @Test
    void simulateModeReplaysThinFileSecondLookApproval() throws Exception {
        mvc.perform(post("/api/v1/decisions").header("X-Simulate", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("app-sim-1", "sim-thin-approve", "PAY_MONTHLY", 1800)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.secondLookApplied").value(true))
                .andExpect(jsonPath("$.bureauUsed").value("EQUIFAX"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.tier").value("B"));
    }

    @Test
    void simulateModeDeclineIncludesAdverseActionNotice() throws Exception {
        mvc.perform(post("/api/v1/decisions").header("X-Simulate", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("app-sim-2", "sim-delinquent", "PAY_MONTHLY", 3000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.secondLookApplied").value(false))
                .andExpect(jsonPath("$.adverseAction.bureauName").value("Experian"))
                .andExpect(jsonPath("$.adverseAction.principalReasons[0]")
                        .value("Serious delinquency or past-due payments reported"));
    }

    @Test
    void retriedApplicationReturnsSameDecision() throws Exception {
        String first = mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("app-idem", "cust-42", "PAY_IN_4", 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(first, "$.decisionId");

        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("app-idem", "cust-42", "PAY_IN_4", 300)))
                .andExpect(jsonPath("$.decisionId").value(id));
        mvc.perform(get("/api/v1/decisions/" + id)).andExpect(status().isOk());
    }

    @Test
    void validatesRequest() throws Exception {
        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(body("app-bad", "cust", "PAY_MONTHLY", 10)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsSimulationScenarios() throws Exception {
        mvc.perform(get("/api/v1/simulations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['sim-prime']").exists());
    }
}
