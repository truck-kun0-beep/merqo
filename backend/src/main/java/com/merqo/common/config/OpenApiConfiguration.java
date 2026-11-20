package com.merqo.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for the OpenAPI page.
 *
 * <p>Responsibility: publish one machine readable description of every endpoint at {@code
 * /v3/api-docs} and render it at {@code /swagger-ui.html}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Factory method</b> - the {@code @Bean} method builds the description object in one
 *       place, so every environment publishes the same contract.
 * </ul>
 */
@Configuration
public class OpenApiConfiguration {

    /**
     * The OpenAPI description of the Merqo API.
     *
     * @return the OpenAPI bean
     */
    @Bean
    public OpenAPI merqoOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Merqo API")
                                .version("v1")
                                .description(
                                        "Omnichannel retail operations and profit intelligence. "
                                                + "All endpoints live under /api/v1 and answer with RFC 7807 problem details on failure.")
                                .contact(new Contact().name("Merqo"))
                                .license(new License().name("Academic project")));
    }
}
