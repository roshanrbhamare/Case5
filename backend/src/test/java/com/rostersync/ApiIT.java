package com.rostersync;

import static com.rostersync.support.Fixtures.currentExport;
import static com.rostersync.support.Fixtures.snapshot;
import static com.rostersync.support.Fixtures.toCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.bootstrap.SeedData;
import com.rostersync.service.ReconcileService;
import com.rostersync.support.IntegrationTest;
import com.rostersync.support.TestDb;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

@IntegrationTest
class ApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ReconcileService reconcile;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        TestDb.reset(Map.of("acme", 4, "globex", 2));
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    MockHttpServletResponse call(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    String tokenFor(String email) throws Exception {
        MockHttpServletResponse res = call(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", SeedData.DEMO_PASSWORD))));
        assertThat(res.getStatus()).isEqualTo(200);
        return "Bearer " + body(res).get("token").asText();
    }

    String events(Object... events) throws Exception {
        return json.writeValueAsString(Map.of("events", List.of(events)));
    }

    @Nested
    class Authentication {

        @Test
        void wrongPasswordGetsAGenericMessage() throws Exception {
            var res = call(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@acme.test\",\"password\":\"nope\"}"));
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(body(res).at("/error/code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(body(res).at("/error/message").asText()).isEqualTo("Email or password is incorrect.");
        }

        @Test
        void requestsWithoutCredentialsOrWithABadApiKeyAreRejected() throws Exception {
            assertThat(call(get("/v1/employees")).getStatus()).isEqualTo(401);
            assertThat(call(post("/v1/events").header("x-api-key", "guess").contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus()).isEqualTo(401);
        }

        @Test
        void aTamperedTokenIsRejected() throws Exception {
            String token = tokenFor("admin@acme.test");
            assertThat(call(get("/v1/employees").header("Authorization", token.substring(0, token.length() - 3) + "abc")).getStatus()).isEqualTo(401);
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void oneClientCanNeverReadAnotherClientsEmployee() throws Exception {
            String acmeAdmin = tokenFor("admin@acme.test");
            String globexAdmin = tokenFor("admin@globex.test");

            assertThat(call(get("/v1/employees/G1001").header("Authorization", acmeAdmin)).getStatus()).isEqualTo(404);
            assertThat(call(get("/v1/employees/G1001").header("Authorization", globexAdmin)).getStatus()).isEqualTo(200);
            JsonNode list = body(call(get("/v1/employees").header("Authorization", acmeAdmin)));
            list.get("employees").forEach(e -> assertThat(e.get("employeeId").asText()).startsWith("E"));
        }

        @Test
        void oneClientCannotSeeAnotherClientsNightlyRunsEvenWithTheRunId() throws Exception {
            var upload = call(post("/v1/reconcile/runs").header("x-api-key", "acme-hr-dev-key").contentType("text/csv")
                    .content(toCsv(currentExport("acme"))));
            String runId = body(upload).get("id").asText();
            String globexOperator = tokenFor("operator@globex.test");

            assertThat(body(call(get("/v1/reconcile/runs").header("Authorization", globexOperator))).get("runs").size()).isZero();
            assertThat(call(get("/v1/reconcile/runs/" + runId).header("Authorization", globexOperator)).getStatus()).isEqualTo(404);
            assertThat(call(get("/v1/reconcile/runs/" + runId).header("Authorization", tokenFor("operator@acme.test"))).getStatus()).isEqualTo(200);
        }

        @Test
        void anHrApiKeyWritesOnlyIntoItsOwnTenant() throws Exception {
            var res = call(post("/v1/events").header("x-api-key", "globex-hr-dev-key").contentType(MediaType.APPLICATION_JSON)
                    .content(events(Map.of("type", "UPDATE", "employeeId", "E1001", "version", 9, "data", snapshot("officeCode", "GGN-CC")))));
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(TestDb.employee("acme", "E1001")).containsEntry("hr_version", 1L);
            assertThat(TestDb.employee("globex", "E1001")).containsEntry("hr_version", 9L);
        }
    }

    @Nested
    class RoleBasedAccess {

        @Test
        void aTransportAdminCannotApproveABlockedRun_anOperatorCan() throws Exception {
            String admin = tokenFor("admin@acme.test");
            String operator = tokenFor("operator@acme.test");
            var upload = call(post("/v1/reconcile/runs").header("Authorization", operator).contentType("text/csv")
                    .content(toCsv(currentExport("acme").subList(0, 1))));
            assertThat(upload.getStatus()).isEqualTo(202);
            assertThat(upload.getHeader("Location")).startsWith("/v1/reconcile/runs/");
            UUID runId = UUID.fromString(body(upload).get("id").asText());
            reconcile.processRun("acme", runId, "inline");

            var denied = call(post("/v1/reconcile/runs/" + runId + "/approve").header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"looks fine\"}"));
            assertThat(denied.getStatus()).isEqualTo(403);
            var approved = call(post("/v1/reconcile/runs/" + runId + "/approve").header("Authorization", operator)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Checked with HR\"}"));
            assertThat(approved.getStatus()).isEqualTo(200);
            assertThat(body(approved).get("status").asText()).isEqualTo("APPROVED");
        }

        @Test
        void onlyTheTransportAdminCanChangePickupPoints_withOptimisticLocking() throws Exception {
            String admin = tokenFor("admin@acme.test");
            String operator = tokenFor("operator@acme.test");
            long version = body(call(get("/v1/employees/E1002").header("Authorization", admin))).get("transportVersion").asLong();

            assertThat(call(patch("/v1/employees/E1002/transport").header("Authorization", operator).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version + ",\"pickupPoint\":\"Gate 5\"}")).getStatus()).isEqualTo(403);

            var ok = call(patch("/v1/employees/E1002/transport").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version + ",\"pickupPoint\":\"Gate 5, Brigade Gateway\"}"));
            assertThat(ok.getStatus()).isEqualTo(200);
            assertThat(body(ok).get("changedFields").get(0).asText()).isEqualTo("pickupPoint");
            assertThat(body(ok).get("transportVersion").asLong()).isEqualTo(version + 1);

            var stale = call(patch("/v1/employees/E1002/transport").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version + ",\"pickupPoint\":\"Somewhere\"}"));
            assertThat(stale.getStatus()).isEqualTo(409);
            assertThat(body(stale).at("/error/code").asText()).isEqualTo("STALE_TRANSPORT_VERSION");

            JsonNode fresh = body(call(get("/v1/employees/E1002").header("Authorization", admin)));
            assertThat(fresh.at("/transport/pickupPoint").asText()).isEqualTo("Gate 5, Brigade Gateway"); // cache was evicted
        }

        @Test
        void anEmployeeSeesOnlyTheirOwnProfileAndBookings() throws Exception {
            String employee = tokenFor("employee@acme.test");
            var me = call(get("/v1/me").header("Authorization", employee));
            assertThat(me.getStatus()).isEqualTo(200);
            assertThat(body(me).at("/employee/employeeId").asText()).isEqualTo("E1001");
            assertThat(body(me).get("bookings").size()).isPositive();
            assertThat(call(get("/v1/employees/E1002").header("Authorization", employee)).getStatus()).isEqualTo(403);
        }

        @Test
        void theHrSystemCanSendEventsButCannotReadEmployees() throws Exception {
            assertThat(call(get("/v1/employees").header("x-api-key", "acme-hr-dev-key")).getStatus()).isEqualTo(403);
        }

        @Test
        void operatorsSeeMaskedContactDetails_adminsSeeThemInFull() throws Exception {
            JsonNode operatorView = body(call(get("/v1/employees/E1001").header("Authorization", tokenFor("operator@acme.test"))));
            JsonNode adminView = body(call(get("/v1/employees/E1001").header("Authorization", tokenFor("admin@acme.test"))));
            assertThat(operatorView.at("/hr/phone").asText()).matches("^•+\\d{4}$");
            assertThat(operatorView.at("/transport/pickupPoint").asText()).isEqualTo("On file");
            assertThat(adminView.at("/hr/phone").asText()).matches("^\\+91\\d{10}$");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void aRejectedRecordNeverEchoesPersonalDataBack() throws Exception {
            var res = call(post("/v1/events").header("x-api-key", "acme-hr-dev-key").contentType(MediaType.APPLICATION_JSON)
                    .content(events(Map.of("type", "CREATE", "employeeId", "E8000", "version", 1,
                            "data", snapshot("phone", "98-SECRET-11", "email", "leak@acme.example", "officeCode", "NOPE")))));
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(body(res).at("/results/0/outcome").asText()).isEqualTo("REJECTED");
            assertThat(res.getContentAsString()).doesNotContain("SECRET").doesNotContain("leak@").doesNotContain("Asha");
        }

        @Test
        void malformedJsonGetsAClear400WithARequestId() throws Exception {
            var res = call(post("/v1/events").header("x-api-key", "acme-hr-dev-key").contentType(MediaType.APPLICATION_JSON).content("{\"events\": ["));
            assertThat(res.getStatus()).isEqualTo(400);
            assertThat(body(res).at("/error/code").asText()).isEqualTo("INVALID_JSON");
            assertThat(body(res).at("/error/requestId").asText()).isNotBlank();
        }

        @Test
        void wrongUploadTypeIsRefused() throws Exception {
            var res = call(post("/v1/reconcile/runs").header("x-api-key", "acme-hr-dev-key").contentType(MediaType.APPLICATION_JSON).content("{\"not\":\"csv\"}"));
            assertThat(res.getStatus()).isEqualTo(415);
        }

        @Test
        void unknownRoutesAndIdsReturn404WithoutStackTraces() throws Exception {
            String admin = tokenFor("admin@acme.test");
            var res = call(get("/v1/reconcile/runs/not-a-uuid").header("Authorization", tokenFor("operator@acme.test")));
            assertThat(res.getStatus()).isEqualTo(404);
            assertThat(call(get("/v1/nope").header("Authorization", admin)).getStatus()).isEqualTo(404);
            assertThat(res.getContentAsString()).doesNotContain("at com.");
        }

        @Test
        void healthAndMetricsEndpointsAreUp() throws Exception {
            assertThat(body(call(get("/health/ready"))).at("/checks/database").asText()).isEqualTo("ok");
            mvc.perform(post("/v1/events").header("x-api-key", "acme-hr-dev-key").contentType(MediaType.APPLICATION_JSON)
                    .content(events(Map.of("type", "DEACTIVATE", "employeeId", "E1004", "version", 2))));
            assertThat(call(get("/metrics")).getContentAsString()).contains("rostersync_events_total");
        }
    }
}
