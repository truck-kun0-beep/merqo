package com.merqo.integration;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.merqo.common.config.MerqoSessionProperties;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.ProblemResponseWriter;
import com.merqo.orders.domain.RequestSignature;
import com.merqo.orders.domain.RequestSignature.VerificationOutcome;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;

/**
 * Stateless authentication filter for {@code /integration/v1/**}.
 *
 * <p>Enforces that callers present valid {@code X-Merqo-Key}, {@code X-Merqo-Timestamp}, and {@code
 * X-Merqo-Signature} headers, rejects browser session cookies, enforces disabled-key and 60 req/min
 * rate limits, and buffers the request body so controllers can read it cleanly.
 */
public class IntegrationAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_KEY = "X-Merqo-Key";
    public static final String HEADER_TIMESTAMP = "X-Merqo-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Merqo-Signature";
    public static final String CLIENT_REQUEST_ATTRIBUTE = "MERQO_INTEGRATION_CLIENT";

    private final IntegrationClientRepository clientRepository;
    private final SecretCipher secretCipher;
    private final IntegrationRateLimiter rateLimiter;
    private final SettingService settingService;
    private final MerqoSessionProperties sessionProperties;
    private final ProblemResponseWriter problemWriter;
    private final Clock clock;

    public IntegrationAuthFilter(
            IntegrationClientRepository clientRepository,
            SecretCipher secretCipher,
            IntegrationRateLimiter rateLimiter,
            SettingService settingService,
            MerqoSessionProperties sessionProperties,
            ProblemResponseWriter problemWriter,
            Clock clock) {
        this.clientRepository = clientRepository;
        this.secretCipher = secretCipher;
        this.rateLimiter = rateLimiter;
        this.settingService = settingService;
        this.sessionProperties = sessionProperties;
        this.problemWriter = problemWriter;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasSessionCookie(request)) {
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.UNAUTHENTICATED,
                    "Browser session cookies are not accepted on integration endpoints.");
            return;
        }

        String keyId = request.getHeader(HEADER_KEY);
        String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
        String signatureHeader = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(keyId) || isBlank(timestampHeader) || isBlank(signatureHeader)) {
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.INVALID_SIGNATURE,
                    "Missing required integration signature headers.");
            return;
        }

        IntegrationClient client = clientRepository.findByKeyId(keyId.trim()).orElse(null);
        if (client == null) {
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.INVALID_SIGNATURE,
                    "Invalid integration key or signature.");
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String secret = secretCipher.decrypt(client.getSecretEncrypted());
        int windowSeconds =
                settingService.get(SettingKey.INTEGRATION_SIGNATURE_WINDOW_SECONDS, Integer.class);
        VerificationOutcome outcome =
                RequestSignature.verify(
                        secret,
                        timestampHeader,
                        cachedRequest.bodyAsString(),
                        signatureHeader,
                        windowSeconds,
                        clock);

        if (outcome == VerificationOutcome.SIGNATURE_EXPIRED) {
            problemWriter.write(
                    cachedRequest,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.SIGNATURE_EXPIRED,
                    "Request timestamp is outside the allowed signature window.");
            return;
        }
        if (outcome != VerificationOutcome.VALID) {
            problemWriter.write(
                    cachedRequest,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.INVALID_SIGNATURE,
                    "Invalid integration signature.");
            return;
        }

        if (!client.isActive()) {
            problemWriter.write(
                    cachedRequest,
                    response,
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.INTEGRATION_DISABLED,
                    "This integration client has been disabled.");
            return;
        }

        if (!rateLimiter.tryConsumeAndTouch(client.getId())) {
            problemWriter.write(
                    cachedRequest,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCodes.RATE_LIMITED,
                    "Integration client exceeded the per-minute request limit.");
            return;
        }

        cachedRequest.setAttribute(CLIENT_REQUEST_ATTRIBUTE, client);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        client.getKeyId(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_INTEGRATION"))));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(cachedRequest, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean hasSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        String sessionCookieName = sessionProperties.getCookieName();
        for (Cookie cookie : cookies) {
            if (sessionCookieName.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
