package com.merqo.orders.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless cryptographic helper for HMAC-SHA256 webhook signature computation and verification.
 *
 * <p>Responsibility: compute and verify {@code HMAC-SHA256(secret, timestamp + "." + body)} encoded
 * as lowercase hexadecimal using constant-time byte comparison and a symmetric timestamp replay
 * window centered on {@link Clock}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - only signing, verification, and replay window checks live
 *       here.
 *   <li><b>Determinism via Clock injection</b> - replay window boundary tests use a fixed {@link
 *       Clock} without sleeps.
 * </ul>
 */
public final class RequestSignature {

    /** Default replay tolerance in seconds (5 minutes in both past and future). */
    public static final int DEFAULT_WINDOW_SECONDS = 300;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private RequestSignature() {}

    /** Outcome of checking a timestamp and HMAC signature. */
    public enum VerificationOutcome {
        VALID,
        SIGNATURE_EXPIRED,
        INVALID_SIGNATURE
    }

    /**
     * Computes lowercase hex HMAC-SHA256 over {@code timestampSeconds + "." + body}.
     *
     * @param secret client secret
     * @param timestampSeconds epoch seconds header value
     * @param body exact request body string
     * @return lowercase 64-character hex signature
     */
    public static String computeHex(String secret, long timestampSeconds, String body) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Secret must not be blank");
        }
        String payload = timestampSeconds + "." + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to compute HMAC-SHA256", ex);
        }
    }

    /**
     * Verifies a request using the default 5-minute (300 s) past/future window.
     *
     * @param secret stored client secret
     * @param timestampHeader raw {@code X-Merqo-Timestamp} header value
     * @param body exact raw request body
     * @param signatureHeader raw {@code X-Merqo-Signature} header value
     * @param clock time source
     * @return verification outcome
     */
    public static VerificationOutcome verify(
            String secret,
            String timestampHeader,
            String body,
            String signatureHeader,
            Clock clock) {
        return verify(
                secret, timestampHeader, body, signatureHeader, DEFAULT_WINDOW_SECONDS, clock);
    }

    /**
     * Verifies a request timestamp window and constant-time HMAC signature.
     *
     * @param secret stored client secret
     * @param timestampHeader raw {@code X-Merqo-Timestamp} header value
     * @param body exact raw request body
     * @param signatureHeader raw {@code X-Merqo-Signature} header value
     * @param windowSeconds maximum allowed drift in either direction (seconds)
     * @param clock time source
     * @return verification outcome
     */
    public static VerificationOutcome verify(
            String secret,
            String timestampHeader,
            String body,
            String signatureHeader,
            int windowSeconds,
            Clock clock) {
        if (secret == null
                || secret.isEmpty()
                || timestampHeader == null
                || timestampHeader.isBlank()
                || signatureHeader == null
                || signatureHeader.isBlank()) {
            return VerificationOutcome.INVALID_SIGNATURE;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException ex) {
            return VerificationOutcome.INVALID_SIGNATURE;
        }

        long nowSeconds = Instant.now(clock).getEpochSecond();
        long drift = Math.abs(nowSeconds - timestamp);
        if (drift > windowSeconds) {
            return VerificationOutcome.SIGNATURE_EXPIRED;
        }

        String normalizedProvided = signatureHeader.trim().toLowerCase(Locale.ROOT);
        if (normalizedProvided.startsWith("sha256=")) {
            normalizedProvided = normalizedProvided.substring("sha256=".length());
        }
        String expectedHex = computeHex(secret, timestamp, body);
        boolean matched =
                MessageDigest.isEqual(
                        expectedHex.getBytes(StandardCharsets.UTF_8),
                        normalizedProvided.getBytes(StandardCharsets.UTF_8));
        return matched ? VerificationOutcome.VALID : VerificationOutcome.INVALID_SIGNATURE;
    }
}
