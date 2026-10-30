package com.rostersync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.bootstrap.SeedData;
import com.rostersync.support.Fixtures;
import com.rostersync.support.TestDb;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Runs every main flow with debug logging on and asserts no personal data reaches the logs. */
@SpringBootTest(properties = {"logging.level.com.rostersync=DEBUG", "logging.level.access=INFO"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionIT {

    static final List<String> PERSONAL = List.of("Zara Khan", "zara.khan@acme.example", "+919876500001", "98765-SECRET", "Flat 12 Secret Residency",
            SeedData.DEMO_PASSWORD);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void logsFromEveryMainFlowContainIdsAndCounts_neverPersonalData(CapturedOutput output) throws Exception {
        TestDb.reset(Map.of("acme", 3));
        String login = mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@acme.test\",\"password\":\"" + SeedData.DEMO_PASSWORD + "\"}")).andReturn().getResponse().getContentAsString();
        String admin = "Bearer " + json.readTree(login).get("token").asText();
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@acme.test\",\"password\":\"wrong-password\"}"));

        Map<String, Object> person = Fixtures.snapshot("fullName", "Zara Khan", "email", "zara.khan@acme.example", "phone", "+919876500001");
        Map<String, Object> badPhone = Fixtures.snapshot("fullName", "Zara Khan", "email", "zara.khan@acme.example", "phone", "98765-SECRET");
        Map<String, Object> withPickup = Fixtures.snapshot("fullName", "Zara Khan", "email", "zara.khan@acme.example", "phone", "+919876500001");
        withPickup.put("pickupPoint", "Flat 12 Secret Residency");
        mvc.perform(post("/v1/events").header("x-api-key", "acme-hr-dev-key").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", List.of(
                        Fixtures.event("CREATE", "E4001", 1, person),
                        Fixtures.event("CREATE", "E4002", 1, badPhone),
                        Fixtures.event("UPDATE", "E4001", 2, withPickup))))));

        mvc.perform(get("/v1/employees").param("search", "Zara Khan").header("Authorization", admin));
        JsonNode current = json.readTree(mvc.perform(get("/v1/employees/E1001").header("Authorization", admin)).andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/v1/employees/E1001/transport").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + current.get("transportVersion").asLong() + ",\"pickupPoint\":\"Flat 12 Secret Residency\"}"));

        String csv = Fixtures.HEADER + "\nE4003,Zara Khan,zara.khan@acme.example,98765-SECRET,,BLR-WF,S0900,1\n";
        mvc.perform(post("/v1/reconcile/runs").header("x-api-key", "acme-hr-dev-key").contentType("text/csv").content(csv));

        String logs = output.getAll();
        assertThat(logs.lines().count()).isGreaterThan(5);
        for (String value : PERSONAL) assertThat(logs).doesNotContain(value);
        assertThat(logs).contains("E4002"); // ids are what we log instead
    }
}
