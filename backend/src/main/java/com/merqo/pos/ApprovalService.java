package com.merqo.pos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.access.PermissionService;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.ValidationException;
import com.merqo.pos.domain.ApprovalPinPolicy;
import com.merqo.pos.domain.ApprovalTokenRules;
import com.merqo.pos.domain.PosRuleException;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.AuthService;

/**
 * Manages manager approval PINs and single-use expiring approval tokens.
 *
 * <p>Responsibility: allow authorized approvers (owner or holders of {@code POS_DISCOUNT_APPROVE})
 * to set their approval PIN, issue single-use 128-bit tokens (storing only SHA-256 hashes), enforce
 * 5-failure / 15-minute lockout throttling, and consume tokens atomically during sale completion.
 *
 * <p>OOP concept: <b>Facade &amp; Delegation</b> — delegates PIN policy to {@link
 * ApprovalPinPolicy}, token validation to {@link ApprovalTokenRules}, and throttle persistence to
 * {@link PinAttemptRecorder}.
 */
@Service
public class ApprovalService {

    public static final int MAX_CONSECUTIVE_FAILURES = 5;
    public static final Duration LOCK_WINDOW = Duration.ofMinutes(15);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;
    private final ApprovalTokenRepository tokenRepository;
    private final PinAttemptRepository pinAttemptRepository;
    private final PinAttemptRecorder pinAttemptRecorder;
    private final AuditService auditService;
    private final Clock clock;

    public ApprovalService(
            AppUserRepository userRepository,
            PermissionService permissionService,
            PasswordEncoder passwordEncoder,
            ApprovalTokenRepository tokenRepository,
            PinAttemptRepository pinAttemptRepository,
            PinAttemptRecorder pinAttemptRecorder,
            AuditService auditService,
            Clock clock) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.passwordEncoder = passwordEncoder;
        this.tokenRepository = tokenRepository;
        this.pinAttemptRepository = pinAttemptRepository;
        this.pinAttemptRecorder = pinAttemptRecorder;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Sets or updates the caller's manager approval PIN after verifying their current password. */
    @Transactional
    public void setApprovalPin(
            AppUser user,
            AccessContext context,
            String currentPassword,
            String rawPin,
            String ipAddress,
            String correlationId) {
        boolean canApproveAnywhere =
                context.isOwner()
                        || !context.visibleLocationIds(Permission.POS_DISCOUNT_APPROVE).isEmpty();
        if (!canApproveAnywhere) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "Only the owner or a user holding POS_DISCOUNT_APPROVE may set an approval PIN.");
        }
        if (!passwordEncoder.matches(
                currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            throw new AuthService.InvalidCredentialsException();
        }
        ApprovalPinPolicy.validate(rawPin);

        Instant now = Instant.now(clock);
        user.setApprovalPinHash(passwordEncoder.encode(rawPin));
        user.setUpdatedAt(now);
        userRepository.save(user);

        auditService.record(
                new AuditEvent.Builder(AuditAction.APPROVAL_PIN_CHANGED, "app_users")
                        .entityId(user.getId())
                        .actor(user.getId(), user.getUsername())
                        .ipAddress(ipAddress)
                        .correlationId(correlationId)
                        .build());
    }

    /**
     * Demo-mode-only PIN assignment helper. Explicitly bypasses weak-sequence checks for the
     * documented demo PIN {@code 1234}.
     */
    @Transactional
    public void setApprovalPinForDemo(AppUser user, String rawPin) {
        // Explicit demo-only policy bypass so demo PIN "1234" works in demo mode.
        ApprovalPinPolicy.validate(rawPin, true);
        user.setApprovalPinHash(passwordEncoder.encode(rawPin));
        user.setUpdatedAt(Instant.now(clock));
        userRepository.save(user);
    }

    /**
     * Verifies an approver PIN at {@code locationId} and issues a single-use 5-minute approval
     * token scoped to the calling cashier and branch.
     */
    @Transactional
    public Map<String, Object> issueApprovalToken(
            AccessContext context,
            UUID locationId,
            String pin,
            BigDecimal maxDiscountPercent,
            Clock overrideClock) {
        Clock effectiveClock = overrideClock != null ? overrideClock : clock;

        if (locationId == null) {
            throw new ValidationException(ErrorCodes.VALIDATION_FAILED, "Location is required.");
        }
        context.requireInScope(locationId);
        if (!context.can(Permission.POS_SELL, locationId)
                && !context.can(Permission.POS_DISCOUNT, locationId)
                && !context.can(Permission.POS_DISCOUNT_APPROVE, locationId)) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN, "You do not have POS permission at this location.");
        }
        if (maxDiscountPercent == null
                || maxDiscountPercent.signum() <= 0
                || maxDiscountPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "maxDiscountPercent must be greater than 0 and at most 100.");
        }

        Instant now = Instant.now(effectiveClock);
        if (isPinLocked(context.getUserId(), now)) {
            throw new PosRuleException(
                    ErrorCodes.PIN_LOCKED,
                    423,
                    "Approval PIN attempts are temporarily locked. Try again later.");
        }

        AppUser matchedApprover = findMatchingApprover(locationId, pin);
        if (matchedApprover == null) {
            pinAttemptRecorder.recordFailure(
                    context.getUserId(), context.getUsername(), locationId, now, "INVALID_PIN");
            throw new PosRuleException(ErrorCodes.APPROVAL_INVALID, 422, "Invalid approval PIN.");
        }

        pinAttemptRecorder.recordSuccess(context.getUserId(), locationId, now);

        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = sha256Hex(rawToken);

        Instant expiresAt = now.plus(ApprovalTokenRules.TOKEN_TTL);
        BigDecimal ceiling = maxDiscountPercent.setScale(2, RoundingMode.HALF_UP);

        ApprovalToken entity =
                new ApprovalToken(
                        UUID.randomUUID(),
                        tokenHash,
                        context.getUserId(),
                        locationId,
                        ceiling,
                        matchedApprover.getId(),
                        now,
                        expiresAt);
        tokenRepository.save(entity);

        auditService.record(
                new AuditEvent.Builder(AuditAction.POS_DISCOUNT_APPROVED, "APPROVAL_TOKEN")
                        .entityId(entity.getId())
                        .locationId(locationId)
                        .actor(context.getUserId(), context.getUsername())
                        .added("approverId", matchedApprover.getId().toString())
                        .added("maxDiscountPercent", ceiling.toPlainString())
                        .build());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approvalToken", rawToken);
        response.put("expiresAt", expiresAt.toString());
        response.put("maxDiscountPercent", ceiling.toPlainString());
        return response;
    }

    /**
     * Validates and atomically consumes a single-use approval token inside the sale transaction.
     */
    @Transactional
    public ApprovalToken consumeToken(
            String rawToken,
            UUID cashierId,
            UUID locationId,
            BigDecimal requestedDiscountPercent,
            Clock overrideClock) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new PosRuleException(
                    ErrorCodes.APPROVAL_INVALID, 422, "Manager approval token is required.");
        }
        Clock effectiveClock = overrideClock != null ? overrideClock : clock;
        String tokenHash = sha256Hex(rawToken.trim());
        ApprovalToken token =
                tokenRepository
                        .findByTokenHashForUpdate(tokenHash)
                        .orElseThrow(
                                () ->
                                        new PosRuleException(
                                                ErrorCodes.APPROVAL_INVALID,
                                                422,
                                                "Manager approval token is invalid, expired, already used, or insufficient."));
        ApprovalTokenRules.validateOrThrow(
                token.toSnapshot(),
                cashierId,
                locationId,
                requestedDiscountPercent,
                effectiveClock);
        token.markUsed(Instant.now(effectiveClock));
        return tokenRepository.save(token);
    }

    private boolean isPinLocked(UUID cashierId, Instant now) {
        Instant windowStart = now.minus(LOCK_WINDOW);
        List<PinAttempt> recent =
                pinAttemptRepository.findByUserIdAndAttemptedAtAfterOrderByAttemptedAtDesc(
                        cashierId, windowStart);
        int consecutiveFailures = 0;
        for (PinAttempt attempt : recent) {
            if (attempt.isSuccess()) {
                break;
            }
            consecutiveFailures++;
        }
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }

    private AppUser findMatchingApprover(UUID locationId, String rawPin) {
        if (rawPin == null || rawPin.isBlank()) {
            return null;
        }
        List<AppUser> users = userRepository.findAll();
        for (AppUser candidate : users) {
            if (!candidate.isActive() || candidate.getApprovalPinHash() == null) {
                continue;
            }
            AccessContext candidateCtx = permissionService.buildContext(candidate);
            if (!candidateCtx.can(Permission.POS_DISCOUNT_APPROVE, locationId)) {
                continue;
            }
            if (passwordEncoder.matches(rawPin, candidate.getApprovalPinHash())) {
                return candidate;
            }
        }
        return null;
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", exception);
        }
    }
}
