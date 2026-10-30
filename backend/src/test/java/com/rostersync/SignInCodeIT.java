package com.rostersync;

import static com.rostersync.support.Fixtures.event;
import static com.rostersync.support.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.support.IntegrationTest;
import com.rostersync.support.TestDb;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Employees sign in with a one-time code for the email the HR feed sent; no accounts are created for them. */
@IntegrationTest
class SignInCodeIT {

    static final String EMAIL = "new.joiner@acme.example";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() throws Exception {
        TestDb.reset(Map.of("acme", 2, "globex", 1));
        hrSends("acme-hr-dev-key", event("CREATE", "E9001", 1, snapshot("fullName", "New Joiner", "email", EMAIL)));
    }

    void hrSends(String apiKey, Object... events) throws Exception {
        var res = mvc.perform(post("/v1/events").header("x-api-key", apiKey).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("events", List.of(events))))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    MockHttpServletResponse postJson(String path, Map<String, String> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
    }

    JsonNode body(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }

    String requestCode(String email) throws Exception {
        JsonNode res = body(postJson("/v1/auth/sign-in-code", Map.of("email", email)));
        assertThat(res.get("sent").asBoolean()).isTrue();
        return res.path("demoCode").asText(null);
    }

    MockHttpServletResponse signIn(String email, String code) throws Exception {
        return postJson("/v1/auth/sign-in-code/verify", Map.of("email", email, "code", code));
    }

    @Test
    void anEmployeeTheHrFeedSentCanSignInWithACode_andSeesOnlyTheirOwnCommute() throws Exception {
        String code = requestCode("  New.Joiner@ACME.example ");
        assertThat(code).matches("\\d{6}");

        var res = signIn(EMAIL, code);
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode result = body(res);
        assertThat(result.at("/user/role").asText()).isEqualTo("EMPLOYEE");
        assertThat(result.at("/user/employeeId").asText()).isEqualTo("E9001");
        assertThat(result.at("/user/tenantId").asText()).isEqualTo("acme");

        String token = "Bearer " + result.get("token").asText();
        assertThat(body(mvc.perform(get("/v1/me").header("Authorization", token)).andReturn().getResponse()).at("/employee/employeeId").asText())
                .isEqualTo("E9001");
        assertThat(mvc.perform(get("/v1/employees").header("Authorization", token)).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aWrongCodeIsRefused_andAUsedCodeCannotBeUsedAgain() throws Exception {
        String code = requestCode(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";

        var res = signIn(EMAIL, wrong);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(body(res).at("/error/message").asText()).isEqualTo("The code is wrong or has expired. Request a new one.");
        assertThat(signIn(EMAIL, code).getStatus()).isEqualTo(200);
        assertThat(signIn(EMAIL, code).getStatus()).isEqualTo(401);
    }

    @Test
    void anExpiredCodeIsRefused() throws Exception {
        String code = requestCode(EMAIL);
        TestDb.exec("UPDATE sign_in_codes SET expires_at = now() - interval '1 second'");
        assertThat(signIn(EMAIL, code).getStatus()).isEqualTo(401);
    }

    @Test
    void fiveWrongAttemptsBurnTheCode() throws Exception {
        String code = requestCode(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) assertThat(signIn(EMAIL, wrong).getStatus()).isEqualTo(401);
        assertThat(signIn(EMAIL, code).getStatus()).isEqualTo(401);
    }

    @Test
    void aNewCodeReplacesTheEarlierOne() throws Exception {
        String first = requestCode(EMAIL);
        String second = requestCode(EMAIL);
        if (!first.equals(second)) assertThat(signIn(EMAIL, first).getStatus()).isEqualTo(401);
        assertThat(signIn(EMAIL, second).getStatus()).isEqualTo(200);
    }

    @Test
    void someoneWhoLeftCanNeitherGetNorUseACode() throws Exception {
        String code = requestCode(EMAIL);
        hrSends("acme-hr-dev-key", event("DEACTIVATE", "E9001", 2));

        assertThat(signIn(EMAIL, code).getStatus()).isEqualTo(401);
        assertThat(requestCode(EMAIL)).isNull();
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerButNoCode() throws Exception {
        var res = postJson("/v1/auth/sign-in-code", Map.of("email", "nobody@acme.example"));
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(body(res).get("sent").asBoolean()).isTrue();
        assertThat(body(res).has("demoCode")).isFalse();
        assertThat(signIn("nobody@acme.example", "123456").getStatus()).isEqualTo(401);
    }

    @Test
    void anEmailThatTwoClientsBothUseGetsNoCode() throws Exception {
        hrSends("globex-hr-dev-key", event("CREATE", "G9001", 1, snapshot("fullName", "Same Email", "email", EMAIL, "officeCode", "GGN-CC")));
        assertThat(requestCode(EMAIL)).isNull();
    }

    @Test
    void onlyAKeyedHashOfTheCodeIsStored() throws Exception {
        String code = requestCode(EMAIL);
        String stored = (String) TestDb.one("SELECT code_hash FROM sign_in_codes WHERE employee_id = 'E9001'").get("code_hash");
        assertThat(stored).hasSize(64).doesNotContain(code);
    }

    @Test
    void malformedRequestsGetA400() throws Exception {
        assertThat(postJson("/v1/auth/sign-in-code", Map.of("email", "not-an-email")).getStatus()).isEqualTo(400);
        assertThat(signIn(EMAIL, "12ab56").getStatus()).isEqualTo(400);
    }
}
