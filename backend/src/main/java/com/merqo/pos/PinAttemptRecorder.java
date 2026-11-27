package com.merqo.pos;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;

/**
 * Records approval PIN throttle attempts and failure audits in independent transactions.
 *
 * <p>Responsibility: ensure failed PIN attempts and {@code POS_APPROVAL_FAILED} audit rows commit
 * even when the outer approval request fails with an exception.
 *
 * <p>OOP concept: <b>Single Responsibility &amp; Transaction Isolation</b> — isolates operational
 * failure recording in {@code REQUIRES_NEW} so rollbacks never erase brute-force evidence.
 */
@Component
public class PinAttemptRecorder {

    private final PinAttemptRepository pinAttemptRepository;
    private final AuditService auditService;

    public PinAttemptRecorder(
            PinAttemptRepository pinAttemptRepository, AuditService auditService) {
        this.pinAttemptRepository = pinAttemptRepository;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            UUID cashierId, String cashierUsername, UUID locationId, Instant now, String reason) {
        pinAttemptRepository.save(
                new PinAttempt(UUID.randomUUID(), cashierId, locationId, false, now));
        auditService.record(
                new AuditEvent(
                        AuditAction.POS_APPROVAL_FAILED,
                        "APPROVAL",
                        null,
                        locationId,
                        cashierId,
                        cashierUsername,
                        null,
                        Map.of("reason", reason),
                        null,
                        null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID cashierId, UUID locationId, Instant now) {
        pinAttemptRepository.save(
                new PinAttempt(UUID.randomUUID(), cashierId, locationId, true, now));
    }
}
