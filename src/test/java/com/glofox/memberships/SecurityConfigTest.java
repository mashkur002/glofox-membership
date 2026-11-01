package com.glofox.memberships;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security chain through the real application context.
 *
 * <p>Credentials are the test fixtures in {@code src/test/resources/config/application.yml}.
 * The context is the same one {@code WiringTest} uses (no bean overrides), so
 * this class adds no extra context startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    private static final String ADMIN = "test-admin";
    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String USER = "test-user";
    private static final String USER_PASSWORD = "test-user-password";
    private static final String MONTHLY_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("no credentials: 401 with a Basic challenge and a problem body")
    void missingCredentialsAre401() throws Exception {
        mvc.perform(get("/v1/plans"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"memberships\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.type").value("urn:glofox:problem:unauthorized"))
                .andExpect(jsonPath("$.instance").value("/v1/plans"));
    }

    @Test
    @DisplayName("a wrong password is 401")
    void wrongPasswordIs401() throws Exception {
        mvc.perform(get("/v1/plans").with(httpBasic(ADMIN, "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    @DisplayName("an unknown user is 401, indistinguishable from a wrong password")
    void unknownUserIs401() throws Exception {
        mvc.perform(get("/v1/plans").with(httpBasic("nobody", USER_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.detail").value("Full authentication is required."));
    }

    @Test
    @DisplayName("ADMIN credentials reach the plans endpoint")
    void adminCanReadPlans() throws Exception {
        mvc.perform(get("/v1/plans").with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("USER credentials reach the plans endpoint")
    void userCanReadPlans() throws Exception {
        mvc.perform(get("/v1/plans").with(httpBasic(USER, USER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("USER can purchase and fetch; the contract is unchanged; no session is created")
    void userCanPurchaseAndFetch() throws Exception {
        String body = """
                {"member_name":"Security Sam","plan_id":"%s","start_date":"%s"}
                """.formatted(MONTHLY_ID, LocalDate.now().plusDays(30));

        String location = mvc.perform(post("/v1/memberships/purchases")
                        .with(httpBasic(USER, USER_PASSWORD))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.member_name").value("Security Sam"))
                .andExpect(jsonPath("$.amount_cents").value(8000))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location).with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member_name").value("Security Sam"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("business errors keep their own problem body for an authenticated caller")
    void businessErrorsAreUnchanged() throws Exception {
        mvc.perform(post("/v1/memberships/purchases")
                        .with(httpBasic(USER, USER_PASSWORD))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    @WithMockUser(roles = "OTHER")
    @DisplayName("an authenticated principal with neither role is 403")
    void otherRoleIs403() throws Exception {
        mvc.perform(get("/v1/plans"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.type").value("urn:glofox:problem:forbidden"));
    }

    @Test
    @DisplayName("paths outside /v1 are denied by default: 401 without credentials")
    void unknownPathWithoutCredentialsIs401() throws Exception {
        mvc.perform(get("/foo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    @DisplayName("paths outside /v1 are denied by default: 403 even for ADMIN")
    void unknownPathWithCredentialsIs403() throws Exception {
        mvc.perform(get("/foo").with(httpBasic(ADMIN, ADMIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    @DisplayName("API docs: the OpenAPI spec is public")
    void openApiSpecIsPublic() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi")));
    }

    @Test
    @DisplayName("API docs: Swagger UI is public (200 or a redirect to its index)")
    void swaggerUiIsPublic() throws Exception {
        int code = mvc.perform(get("/swagger-ui.html"))
                .andReturn().getResponse().getStatus();
        assertThat(code).isBetween(200, 399);
    }

    @Test
    @DisplayName("API docs: the ReDoc page is public")
    void redocIsPublic() throws Exception {
        mvc.perform(get("/redoc.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("API docs being public does not open the API: /v1/plans is still 401")
    void apiStillNeedsCredentialsWhenDocsArePublic() throws Exception {
        mvc.perform(get("/v1/plans"))
                .andExpect(status().isUnauthorized());
    }
}
