package com.glofox.memberships.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata for springdoc.
 *
 * <p>The spec and the documentation pages are public; the API itself is not.
 * Every operation is marked as requiring the {@code basicAuth} scheme, matching
 * {@link SecurityConfig}, so Swagger UI offers an "Authorize" button.
 */
@Configuration
public class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    static final String PURCHASES_PATH = "/v1/memberships/purchases";
    static final String EXAMPLE_MEMBER_NAME = "Alice";
    static final String EXAMPLE_PLAN_ID = "11111111-1111-1111-1111-111111111111";
    static final int EXAMPLE_START_DATE_DAYS_AHEAD = 30;

    @Bean
    OpenAPI membershipsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Glofox Memberships Purchase API")
                        .version("0.0.1-SNAPSHOT"))
                .components(new Components()
                        .addSecuritySchemes(BASIC_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }

    /**
     * Documents schemas in snake_case, matching the wire format.
     *
     * <p>{@code spring.jackson.property-naming-strategy} configures Spring's
     * Jackson 3 mapper, which serialises the real responses. swagger-core builds
     * schemas with its own Jackson 2 mapper ({@link Json#mapper()}), which never
     * sees that property. Without this bean the docs would show
     * {@code memberName} while the API expects {@code member_name}.
     *
     * <p>springdoc's {@code ModelConverterRegistrar} collects every
     * {@code ModelConverter} bean, removes the default converter of the same
     * class, and registers this one in its place. The mapper is a
     * <em>copy</em>, so swagger-core's shared mapper is untouched, and Spring's
     * runtime serialisation is a separate Jackson 3 mapper entirely. This bean
     * affects documentation only.
     */
    @Bean
    ModelResolver snakeCaseModelResolver(SpringDocConfigProperties springDocConfig) {
        return new ModelResolver(Json.mapper().copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
                .openapi31(springDocConfig.isOpenapi31());
    }

    /**
     * A ready-to-send request example for "Try it out".
     *
     * <p>{@code start_date} is today + {@value #EXAMPLE_START_DATE_DAYS_AHEAD}
     * days rather than a fixed date: purchases reject past start dates, so a
     * literal date would eventually turn the example into a guaranteed 400.
     */
    @Bean
    OpenApiCustomizer purchaseRequestExample(Clock clock) {
        return openApi -> {
            PathItem path = openApi.getPaths() == null ? null : openApi.getPaths().get(PURCHASES_PATH);
            Operation post = path == null ? null : path.getPost();
            RequestBody body = post == null ? null : post.getRequestBody();
            if (body == null || body.getContent() == null) {
                return;
            }
            MediaType json = body.getContent().get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
            if (json == null) {
                return;
            }
            Map<String, Object> example = new LinkedHashMap<>();
            example.put("member_name", EXAMPLE_MEMBER_NAME);
            example.put("plan_id", EXAMPLE_PLAN_ID);
            example.put("start_date",
                    LocalDate.now(clock).plusDays(EXAMPLE_START_DATE_DAYS_AHEAD).toString());
            json.setExample(example);
        };
    }
}
