package dev.saseq.configs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class BearerAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(BearerAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";

    private final String expectedToken;

    public BearerAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            logRejection(request, "missing header");
            rejectRequest(response);
            return;
        }

        if (!authorizationHeader.startsWith(BEARER_PREFIX)) {
            logRejection(request, "wrong scheme");
            rejectRequest(response);
            return;
        }

        String providedToken = authorizationHeader.substring(BEARER_PREFIX.length());

        if (!tokensEqual(expectedToken, providedToken)) {
            logRejection(request, "wrong token");
            rejectRequest(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean tokensEqual(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private void rejectRequest(HttpServletResponse response) throws IOException {
        response.setHeader(WWW_AUTHENTICATE_HEADER, "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private void logRejection(HttpServletRequest request, String reason) {
        String remoteAddr = request.getRemoteAddr();
        log.warn("Bearer token authentication rejected from {}: {}", remoteAddr, reason);
    }
}
