package com.merqo.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.merqo.common.money.Money;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.payments.domain.MockPaymentVerifier.SimulatedTransaction;
import com.merqo.payments.domain.MockPaymentVerifier.SimulatedTransactionStore;
import com.merqo.payments.domain.PaymentVerifier.PaymentClaim;
import com.merqo.payments.domain.PaymentVerifier.VerificationKind;
import com.merqo.payments.domain.PaymentVerifier.VerificationResult;

/** Unit tests (no Spring) for online payment rules, verification policies, and positions. */
class PaymentAndVerificationDomainTest {

    @Test
    @DisplayName("method reference rules normalize valid inputs and reject malformed or PAN values")
    void methodReferenceRules() {
        assertEquals(
                "TX9876AB", OnlinePaymentMethod.BKASH.normalizeAndValidateReference(" tx9876ab "));
        assertEquals(
                "NG1234CD", OnlinePaymentMethod.NAGAD.normalizeAndValidateReference("ng1234cd"));
        assertEquals("9876", OnlinePaymentMethod.CARD.normalizeAndValidateReference(" 9876 "));
        assertEquals(
                "AUTH1234", OnlinePaymentMethod.CARD.normalizeAndValidateReference("auth1234"));
        assertEquals(
                "BNK-2026-01",
                OnlinePaymentMethod.BANK.normalizeAndValidateReference("bnk-2026-01"));

        // Too short or invalid format
        assertEquals(
                "PAYMENT_REFERENCE_REQUIRED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        OnlinePaymentMethod.BKASH.normalizeAndValidateReference(
                                                "   "))
                        .getErrorCode());
        assertEquals(
                "VALIDATION_FAILED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        OnlinePaymentMethod.BKASH.normalizeAndValidateReference(
                                                "AB12"))
                        .getErrorCode());
        assertEquals(
                "VALIDATION_FAILED",
                assertThrows(
                                PaymentRuleException.class,
                                () -> OnlinePaymentMethod.CARD.normalizeAndValidateReference("98"))
                        .getErrorCode());
        assertEquals(
                "VALIDATION_FAILED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        OnlinePaymentMethod.BANK.normalizeAndValidateReference(
                                                "----"))
                        .getErrorCode());

        // Card-number-like 13, 16, 19 digits with spaces/dashes and 12-digit Luhn-valid TrxID
        for (String panLike :
                new String[] {
                    "4111111111111",
                    "4111 1111 1111 1111",
                    "4111-1111-1111-1111-123",
                    "411111111117" // 12-digit Luhn-valid number submitted as TrxID
                }) {
            for (OnlinePaymentMethod method : OnlinePaymentMethod.values()) {
                PaymentRuleException ex =
                        assertThrows(
                                PaymentRuleException.class,
                                () -> method.normalizeAndValidateReference(panLike));
                assertEquals("CARD_NUMBER_REJECTED", ex.getErrorCode());
            }
        }

        // Embedded PAN inside free text notes rejected as well
        assertEquals(
                "CARD_NUMBER_REJECTED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        CardNumberGuard.rejectCardNumberInText(
                                                "Customer card 4111-1111-1111-1111 paid"))
                        .getErrorCode());
    }

    @Test
    @DisplayName("RefundPayoutMethod enforces CASH note-only rules and delegates non-cash channels")
    void refundPayoutMethodRules() {
        RefundPayoutMethod.ValidatedPayout cashOk =
                RefundPayoutMethod.CASH.validateAndNormalize(null, " Paid in cash at desk ");
        assertNull(cashOk.normalizedReference());
        assertEquals("Paid in cash at desk", cashOk.note());

        assertEquals(
                "VALIDATION_FAILED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RefundPayoutMethod.CASH.validateAndNormalize(
                                                "TRX1234", "Note"))
                        .getErrorCode());
        assertEquals(
                "VALIDATION_FAILED",
                assertThrows(
                                PaymentRuleException.class,
                                () -> RefundPayoutMethod.CASH.validateAndNormalize(null, "  "))
                        .getErrorCode());

        RefundPayoutMethod.ValidatedPayout bkashOk =
                RefundPayoutMethod.BKASH.validateAndNormalize("bk998877", null);
        assertEquals("BK998877", bkashOk.normalizedReference());
    }

    @Test
    @DisplayName("PaymentStatus derives strictly from optional PaymentDecision")
    void paymentStatusDerivation() {
        assertEquals(PaymentStatus.PENDING_VERIFICATION, PaymentStatus.derive(null));
        assertEquals(PaymentStatus.VERIFIED, PaymentStatus.derive(PaymentDecision.VERIFIED));
        assertEquals(PaymentStatus.REJECTED, PaymentStatus.derive(PaymentDecision.REJECTED));
    }

    @Test
    @DisplayName("RecordedPaymentRules enforces COD prohibition and non-rejected prepayment caps")
    void recordedPaymentRulesCapsAndModes() {
        ExpectedPrepayment cod =
                ExpectedPrepayment.from(
                        PaymentMode.COD, Money.of(1000), Money.ZERO, Money.of(1000));
        assertEquals(Money.ZERO, cod.requiredPrepayment());
        assertEquals(
                "PAYMENT_NOT_EXPECTED",
                assertThrows(
                                PaymentRuleException.class,
                                () -> RecordedPaymentRules.validate(cod, Money.ZERO, Money.of(100)))
                        .getErrorCode());

        ExpectedPrepayment advanceCod =
                ExpectedPrepayment.from(
                        PaymentMode.ADVANCE_COD, Money.of(1000), Money.of(200), Money.of(800));
        assertEquals(Money.of(200), advanceCod.requiredPrepayment());
        RecordedPaymentRules.validate(advanceCod, Money.ZERO, Money.of(200));
        RecordedPaymentRules.validate(advanceCod, Money.of(120), Money.of(80));
        assertEquals(
                "PAYMENT_EXCEEDS_TOTAL",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RecordedPaymentRules.validate(
                                                advanceCod, Money.of(120), Money.of(81)))
                        .getErrorCode());

        ExpectedPrepayment prepaid =
                ExpectedPrepayment.from(
                        PaymentMode.FULL_PREPAID, Money.of(1500), Money.of(1500), Money.ZERO);
        RecordedPaymentRules.validate(prepaid, Money.of(500), Money.of(1000));
        assertEquals(
                "PAYMENT_EXCEEDS_TOTAL",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RecordedPaymentRules.validate(
                                                prepaid, Money.of(500), Money.of(1001)))
                        .getErrorCode());
    }

    @Test
    @DisplayName(
            "OrderPaymentPosition computes verified, pending, rejected, refunds, readiness, and COD balances")
    void orderPaymentPositionMatrix() {
        ExpectedPrepayment expected =
                ExpectedPrepayment.from(
                        PaymentMode.ADVANCE_COD, Money.of(2000), Money.of(500), Money.of(1500));

        // Partial verified + pending + rejected -> NOT readyForShipment yet
        OrderPaymentPosition partial =
                OrderPaymentPosition.compute(
                        expected,
                        Money.of(300),
                        Money.of(200),
                        Money.of(500),
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO);
        assertFalse(partial.prepaymentSatisfied());
        assertFalse(partial.readyForShipment());
        assertEquals(Money.of(200), partial.shortfall());
        assertEquals(Money.of(300), partial.netHeld());
        assertEquals(Money.of(300), partial.refundable());
        assertEquals(Money.of(1500), partial.codUncollected());

        // Flips readyForShipment when verifiedPaid reaches requiredPrepayment, even with open/paid
        // refunds tracked accurately
        OrderPaymentPosition fulfilledWithRefundAndCod =
                OrderPaymentPosition.compute(
                        expected,
                        Money.of(500),
                        Money.ZERO,
                        Money.of(500),
                        Money.of(100),
                        Money.of(150),
                        Money.of(1500),
                        Money.of(900),
                        Money.of(15));
        assertTrue(fulfilledWithRefundAndCod.prepaymentSatisfied());
        assertTrue(fulfilledWithRefundAndCod.readyForShipment());
        assertEquals(Money.ZERO, fulfilledWithRefundAndCod.shortfall());
        assertEquals(Money.of(400), fulfilledWithRefundAndCod.netHeld());
        assertEquals(Money.of(250), fulfilledWithRefundAndCod.refundable());
        assertEquals(Money.of(600), fulfilledWithRefundAndCod.codWithCourier());
        assertEquals(Money.ZERO, fulfilledWithRefundAndCod.codUncollected());
        assertEquals(Money.of(15), fulfilledWithRefundAndCod.codFeesDeducted());

        // Zero-prepayment COD order is readyForShipment immediately
        OrderPaymentPosition codOnly =
                OrderPaymentPosition.compute(
                        ExpectedPrepayment.from(
                                PaymentMode.COD, Money.of(900), Money.ZERO, Money.of(900)),
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO);
        assertTrue(codOnly.readyForShipment());
    }

    @Test
    @DisplayName(
            "VerificationPolicy enforces PAYMENT_VERIFY, separate verifier setting, and owner override")
    void verificationPolicyRules() {
        UUID recorder = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();

        // Missing permission -> 403
        assertEquals(
                "FORBIDDEN",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        VerificationPolicy.assertCanDecide(
                                                verifier, false, false, recorder, false))
                        .getErrorCode());

        // Setting OFF -> self verification allowed with permission
        VerificationPolicy.assertCanDecide(recorder, false, true, recorder, false);

        // Setting ON -> self verification blocked for non-owner
        assertEquals(
                "SELF_VERIFICATION_NOT_ALLOWED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        VerificationPolicy.assertCanDecide(
                                                recorder, false, true, recorder, true))
                        .getErrorCode());

        // Different user -> allowed
        VerificationPolicy.assertCanDecide(verifier, false, true, recorder, true);

        // Owner -> always allowed even on own recorded payment
        VerificationPolicy.assertCanDecide(recorder, true, false, recorder, true);
    }

    @Test
    @DisplayName(
            "PaymentVerifierRegistry selects manual/mock, fails on live, and MockVerifier validates outcomes")
    void verifierRegistryAndStrategies() {
        ManualPaymentVerifier manual = new ManualPaymentVerifier();
        BkashPaymentVerifier bkashStub = new BkashPaymentVerifier();
        Map<String, SimulatedTransaction> txs = new HashMap<>();
        MockPaymentVerifier mock =
                new MockPaymentVerifier(
                        new SimulatedTransactionStore() {
                            @Override
                            public Optional<SimulatedTransaction> findByMethodAndReference(
                                    OnlinePaymentMethod method, String reference) {
                                return Optional.ofNullable(
                                        txs.get(method.name() + ":" + reference));
                            }

                            @Override
                            public void markClaimed(UUID transactionId, UUID paymentId) {
                                txs.replaceAll(
                                        (k, v) ->
                                                v.id().equals(transactionId)
                                                        ? new SimulatedTransaction(
                                                                v.id(),
                                                                v.method(),
                                                                v.reference(),
                                                                v.amount(),
                                                                paymentId)
                                                        : v);
                            }
                        });

        // Blank defaults to manual
        PaymentVerifierRegistry defaultReg =
                new PaymentVerifierRegistry(null, manual, mock, bkashStub);
        assertEquals("manual", defaultReg.getMode());
        assertFalse(defaultReg.isProviderCheckEnabled());
        assertEquals(
                VerificationKind.UNAVAILABLE,
                defaultReg
                        .getVerifier()
                        .verify(
                                new PaymentClaim(
                                        UUID.randomUUID(),
                                        OnlinePaymentMethod.BKASH,
                                        "TRX1234",
                                        Money.of(100),
                                        "ORD-1"))
                        .kind());

        // live fails startup cleanly
        IllegalStateException liveEx =
                assertThrows(
                        IllegalStateException.class,
                        () -> new PaymentVerifierRegistry("live", manual, mock, bkashStub));
        assertTrue(liveEx.getMessage().contains("merchant API access"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        bkashStub.verify(
                                new PaymentClaim(
                                        UUID.randomUUID(),
                                        OnlinePaymentMethod.BKASH,
                                        "TRX1234",
                                        Money.of(100),
                                        "ORD-1")));

        // mock outcomes
        PaymentVerifierRegistry mockReg =
                new PaymentVerifierRegistry("mock", manual, mock, bkashStub);
        assertTrue(mockReg.isProviderCheckEnabled());

        UUID txId = UUID.randomUUID();
        txs.put(
                "BKASH:TRX9999",
                new SimulatedTransaction(
                        txId,
                        OnlinePaymentMethod.BKASH,
                        "TRX9999",
                        Money.of(new BigDecimal("500.00")),
                        null));

        UUID p1 = UUID.randomUUID();
        assertEquals(
                VerificationKind.NOT_FOUND,
                mock.verify(
                                new PaymentClaim(
                                        p1,
                                        OnlinePaymentMethod.BKASH,
                                        "MISSING1",
                                        Money.of(500),
                                        "ORD-1"))
                        .kind());
        assertEquals(
                VerificationKind.AMOUNT_MISMATCH,
                mock.verify(
                                new PaymentClaim(
                                        p1,
                                        OnlinePaymentMethod.BKASH,
                                        "TRX9999",
                                        Money.of(400),
                                        "ORD-1"))
                        .kind());
        VerificationResult confirmed =
                mock.verify(
                        new PaymentClaim(
                                p1, OnlinePaymentMethod.BKASH, "TRX9999", Money.of(500), "ORD-1"));
        assertEquals(VerificationKind.CONFIRMED, confirmed.kind());
        assertEquals("TRX9999", confirmed.providerReference());

        // Another payment cannot claim the same mock transaction
        UUID p2 = UUID.randomUUID();
        assertEquals(
                VerificationKind.ALREADY_CLAIMED,
                mock.verify(
                                new PaymentClaim(
                                        p2,
                                        OnlinePaymentMethod.BKASH,
                                        "TRX9999",
                                        Money.of(500),
                                        "ORD-2"))
                        .kind());
    }
}
