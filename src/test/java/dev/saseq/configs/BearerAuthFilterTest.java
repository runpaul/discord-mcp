package dev.saseq.configs;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class BearerAuthFilterTest {
    private static final String VALID_TOKEN = "my-secret-token";
    private BearerAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new BearerAuthFilter(VALID_TOKEN);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @Test
    void testMissingHeader() throws ServletException, IOException {
        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertNull(chain.getRequest());
    }

    @Test
    void testWrongToken() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer wrong-token");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertNull(chain.getRequest());
    }

    @Test
    void testWrongScheme() throws ServletException, IOException {
        request.addHeader("Authorization", "Basic abc123");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertNull(chain.getRequest());
    }

    @Test
    void testCorrectToken() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void testBlankHeader() throws ServletException, IOException {
        request.addHeader("Authorization", "   ");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void testConstantTimeComparison() throws ServletException, IOException {
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
