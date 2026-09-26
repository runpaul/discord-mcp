package dev.saseq.configs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.*;

class AuthConfigTest {
    private AuthConfig authConfig;

    @BeforeEach
    void setUp() {
        authConfig = new AuthConfig();
    }

    @Test
    void testBlankTokenThrowsException() {
        assertThrows(IllegalStateException.class, () -> {
            authConfig.bearerAuthFilterRegistration("");
        });
    }

    @Test
    void testNullTokenThrowsException() {
        assertThrows(IllegalStateException.class, () -> {
            authConfig.bearerAuthFilterRegistration(null);
        });
    }

    @Test
    void testValidTokenCreatesRegistration() {
        String token = "valid-secret-token";
        FilterRegistrationBean<BearerAuthFilter> registration =
                authConfig.bearerAuthFilterRegistration(token);

        assertNotNull(registration);
        assertNotNull(registration.getFilter());
    }

    @Test
    void testRegistrationUrlPatterns() {
        String token = "valid-secret-token";
        FilterRegistrationBean<BearerAuthFilter> registration =
                authConfig.bearerAuthFilterRegistration(token);

        String[] patterns = registration.getUrlPatterns().toArray(new String[0]);
        assertEquals(2, patterns.length);
        assertArrayContains(patterns, "/mcp");
        assertArrayContains(patterns, "/mcp/*");
    }

    @Test
    void testRegistrationOrder() {
        String token = "valid-secret-token";
        FilterRegistrationBean<BearerAuthFilter> registration =
                authConfig.bearerAuthFilterRegistration(token);

        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
    }

    @Test
    void testActuatorHealthNotMatched() {
        String token = "valid-secret-token";
        FilterRegistrationBean<BearerAuthFilter> registration =
                authConfig.bearerAuthFilterRegistration(token);

        String[] patterns = registration.getUrlPatterns().toArray(new String[0]);
        assertFalse(arrayContains(patterns, "/actuator/health"));
    }

    private void assertArrayContains(String[] array, String value) {
        assertTrue(arrayContains(array, value),
                "Array does not contain expected value: " + value);
    }

    private boolean arrayContains(String[] array, String value) {
        for (String item : array) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
