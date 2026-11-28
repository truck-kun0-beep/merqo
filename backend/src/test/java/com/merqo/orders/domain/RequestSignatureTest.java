package com.merqo.orders.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestSignatureTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-28T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String SECRET = "super-secret-webhook-key";
    private static final String BODY = "{\"externalOrderId\":\"WEB-1001\",\"total\":1250.00}";

    @Test
    @DisplayName("accepts valid signature inside 5-minute past and future window")
    void validSignatureAndWindowEdges() {
        long nowSec = FIXED_NOW.getEpochSecond();
        assertEquals(
                RequestSignature.VerificationOutcome.VALID,
                RequestSignature.verify(
                        SECRET,
                        String.valueOf(nowSec),
                        BODY,
                        RequestSignature.computeHex(SECRET, nowSec, BODY),
                        FIXED_CLOCK));

        // Upper and lower boundaries (+/- 300s) accepted
        long pastEdge = nowSec - 300;
        assertEquals(
                RequestSignature.VerificationOutcome.VALID,
                RequestSignature.verify(
                        SECRET,
                        String.valueOf(pastEdge),
                        BODY,
                        RequestSignature.computeHex(SECRET, pastEdge, BODY),
                        FIXED_CLOCK));

        long futureEdge = nowSec + 300;
        assertEquals(
                RequestSignature.VerificationOutcome.VALID,
                RequestSignature.verify(
                        SECRET,
                        String.valueOf(futureEdge),
                        BODY,
                        RequestSignature.computeHex(SECRET, futureEdge, BODY),
                        FIXED_CLOCK));
    }

    @Test
    @DisplayName("rejects one wrong character, wrong timestamp, and expired past/future windows")
    void tamperedAndExpiredSignaturesRejected() {
        long nowSec = FIXED_NOW.getEpochSecond();
        String validSig = RequestSignature.computeHex(SECRET, nowSec, BODY);

        // Tamper a single hex character
        char flipped = validSig.charAt(0) == 'a' ? 'b' : 'a';
        String oneCharWrong = flipped + validSig.substring(1);
        assertEquals(
                RequestSignature.VerificationOutcome.INVALID_SIGNATURE,
                RequestSignature.verify(
                        SECRET, String.valueOf(nowSec), BODY, oneCharWrong, FIXED_CLOCK));

        // Signature computed for timestamp t verified with different timestamp header t+1
        assertEquals(
                RequestSignature.VerificationOutcome.INVALID_SIGNATURE,
                RequestSignature.verify(
                        SECRET, String.valueOf(nowSec + 1), BODY, validSig, FIXED_CLOCK));

        // Window exceeded in past (-301s) and future (+301s)
        long expiredPast = nowSec - 301;
        assertEquals(
                RequestSignature.VerificationOutcome.SIGNATURE_EXPIRED,
                RequestSignature.verify(
                        SECRET,
                        String.valueOf(expiredPast),
                        BODY,
                        RequestSignature.computeHex(SECRET, expiredPast, BODY),
                        FIXED_CLOCK));

        long expiredFuture = nowSec + 301;
        assertEquals(
                RequestSignature.VerificationOutcome.SIGNATURE_EXPIRED,
                RequestSignature.verify(
                        SECRET,
                        String.valueOf(expiredFuture),
                        BODY,
                        RequestSignature.computeHex(SECRET, expiredFuture, BODY),
                        FIXED_CLOCK));

        // Malformed timestamp
        assertEquals(
                RequestSignature.VerificationOutcome.INVALID_SIGNATURE,
                RequestSignature.verify(SECRET, "not-a-timestamp", BODY, validSig, FIXED_CLOCK));
    }
}
