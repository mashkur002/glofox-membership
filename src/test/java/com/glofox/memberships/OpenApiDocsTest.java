package com.glofox.memberships;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published API documentation matches the API it documents.
 *
 * <p>Uses the same plain context as {@code SecurityConfigTest} and
 * {@code WiringTest} (no bean overrides), so it adds no context startup. No
 * other test in that context purchases for "Alice", so the one purchase made
 * here cannot collide on the one-unlimited-per-member rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    private static final String USER = "test-user";
    private static final String USER_PASSWORD = "test-user-password";
    private static final String PURCHASE_EXAMPLE =
            "$.paths['/v1/memberships/purchases'].post.requestBody.content['application/json'].example";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Clock clock;

    private String apiDocs() throws Exception {
        return mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Object> properties(String docs, String schema) {
        return JsonPath.read(docs, "$.components.schemas." + schema + ".properties");
    }

    @Test
    @DisplayName("PurchaseRequest is documented in snake_case, matching the wire format")
    void purchaseRequestSchemaIsSnakeCase() throws Exception {
        Map<String, Object> props = properties(apiDocs(), "PurchaseRequest");

        assertThat(props).containsKeys("member_name", "plan_id", "start_date")
                .doesNotContainKeys("memberName", "planId", "startDate");
    }

    @Test
    @DisplayName("PurchaseResponse and PlanResponse document no camelCase property")
    void responseSchemasHaveNoCamelCase() throws Exception {
        String docs = apiDocs();

        for (String schema : new String[] {"PurchaseResponse", "PlanResponse"}) {
            Map<String, Object> props = properties(docs, schema);
            assertThat(props).as(schema + " properties").isNotEmpty();
            assertThat(props.keySet()).as(schema + " property names")
                    .allSatisfy(name -> assertThat(name).matches("[a-z0-9_]+"));
        }
    }

    @Test
    @DisplayName("the purchase request example is the snake_case sample with a future start date")
    void purchaseRequestExampleIsSnakeCaseSample() throws Exception {
        Map<String, Object> example = JsonPath.read(apiDocs(), PURCHASE_EXAMPLE);

        assertThat(example).containsExactly(
                Map.entry("member_name", "Alice"),
                Map.entry("plan_id", "11111111-1111-1111-1111-111111111111"),
                Map.entry("start_date", LocalDate.now(clock).plusDays(30).toString()));
    }

    @Test
    @DisplayName("posting the documented example verbatim with Basic auth is 201")
    void documentedExampleIsAccepted() throws Exception {
        Map<String, Object> example = JsonPath.read(apiDocs(), PURCHASE_EXAMPLE);

        mvc.perform(post("/v1/memberships/purchases")
                        .with(httpBasic(USER, USER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(example)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("/redoc.html is public and loads ReDoc 2.x from jsDelivr against /v3/api-docs")
    void redocPageLoadsPinnedBundle() throws Exception {
        String page = mvc.perform(get("/redoc.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("https://cdn.jsdelivr.net/npm/redoc@2.5.4/bundles/redoc.standalone.js")
                .contains("spec-url=\"/v3/api-docs\"")
                .doesNotContain("cdn.redoc.ly");
    }
}
