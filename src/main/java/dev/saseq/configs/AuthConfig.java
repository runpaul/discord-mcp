package dev.saseq.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

@Configuration
@Profile("http")
public class AuthConfig {
    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    @Bean
    public FilterRegistrationBean<BearerAuthFilter> bearerAuthFilterRegistration(
            @Value("${MCP_BEARER_TOKEN:}") String token) {

        if (token == null || token.isBlank()) {
            log.error("MCP_BEARER_TOKEN must be set in the http profile");
            throw new IllegalStateException("MCP_BEARER_TOKEN must be set in the http profile");
        }

        BearerAuthFilter filter = new BearerAuthFilter(token);
        FilterRegistrationBean<BearerAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);

        return registration;
    }
}
