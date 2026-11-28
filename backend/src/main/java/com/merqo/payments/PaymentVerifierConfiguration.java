package com.merqo.payments;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.merqo.common.config.MerqoModeProperties;
import com.merqo.common.money.Money;
import com.merqo.payments.domain.BkashPaymentVerifier;
import com.merqo.payments.domain.ManualPaymentVerifier;
import com.merqo.payments.domain.MockPaymentVerifier;
import com.merqo.payments.domain.MockPaymentVerifier.SimulatedTransaction;
import com.merqo.payments.domain.MockPaymentVerifier.SimulatedTransactionStore;
import com.merqo.payments.domain.OnlinePaymentMethod;
import com.merqo.payments.domain.PaymentVerifierRegistry;

/** Spring configuration wiring {@link PaymentVerifierRegistry} from {@code PAYMENT_MODE}. */
@Configuration
public class PaymentVerifierConfiguration {

    @Bean
    public PaymentVerifierRegistry paymentVerifierRegistry(
            MerqoModeProperties modeProperties, MockProviderTransactionRepository mockRepository) {
        SimulatedTransactionStore store =
                new SimulatedTransactionStore() {
                    @Override
                    public Optional<SimulatedTransaction> findByMethodAndReference(
                            OnlinePaymentMethod method, String reference) {
                        return mockRepository
                                .findByMethodAndReferenceForUpdate(method, reference)
                                .map(
                                        tx ->
                                                new SimulatedTransaction(
                                                        tx.getId(),
                                                        tx.getMethod(),
                                                        tx.getReference(),
                                                        Money.of(tx.getAmount()),
                                                        tx.getClaimedByPaymentId()));
                    }

                    @Override
                    public void markClaimed(UUID transactionId, UUID paymentId) {
                        mockRepository
                                .findById(transactionId)
                                .ifPresent(
                                        tx -> {
                                            tx.setClaimedByPaymentId(paymentId);
                                            mockRepository.saveAndFlush(tx);
                                        });
                    }
                };
        return new PaymentVerifierRegistry(
                modeProperties.getPaymentMode(),
                new ManualPaymentVerifier(),
                new MockPaymentVerifier(store),
                new BkashPaymentVerifier());
    }
}
