package com.merqo.orders;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PhoneHistory;
import com.merqo.orders.domain.PhoneRiskPolicy;
import com.merqo.orders.domain.PhoneRiskPolicy.RiskTier;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;

/** Computes derived customer order history and refusal-risk badges from {@code orders}. */
@Service
public class PhoneRiskService {

    private final OrderRepository orderRepository;
    private final SettingService settingService;

    public PhoneRiskService(OrderRepository orderRepository, SettingService settingService) {
        this.orderRepository = orderRepository;
        this.settingService = settingService;
    }

    /**
     * Evaluates history for {@code phoneNormalized}, optionally excluding {@code excludeOrderId}
     * (so viewing a customer's first pending order shows {@code NEW} rather than counting itself).
     */
    @Transactional(readOnly = true)
    public PhoneRiskSnapshot evaluate(String phoneNormalized, UUID excludeOrderId) {
        if (phoneNormalized == null || phoneNormalized.isBlank()) {
            return new PhoneRiskSnapshot(null, PhoneHistory.EMPTY, RiskTier.NEW, List.of());
        }
        List<Order> matching =
                orderRepository.findByPhoneNormalizedOrderByCreatedAtDesc(phoneNormalized);
        List<Order> historyOrders =
                excludeOrderId == null
                        ? matching
                        : matching.stream().filter(o -> !o.getId().equals(excludeOrderId)).toList();

        int delivered = 0;
        int cancelled = 0;
        int declined = 0;
        int refused = 0;
        int returned = 0;
        for (Order o : historyOrders) {
            OrderStatus st = o.getStatus();
            if (st == OrderStatus.DELIVERED) {
                delivered++;
            } else if (st == OrderStatus.CANCELLED) {
                cancelled++;
            } else if (st == OrderStatus.DECLINED) {
                declined++;
            } else if (st == OrderStatus.REFUSED) {
                refused++;
            } else if (st == OrderStatus.RETURNED) {
                returned++;
            }
        }
        PhoneHistory history =
                new PhoneHistory(
                        delivered, cancelled, declined, refused, returned, historyOrders.size());
        int threshold = settingService.get(SettingKey.THRESHOLD_REFUSAL_RISK_COUNT, Integer.class);
        RiskTier riskTier = PhoneRiskPolicy.evaluate(history, threshold);
        return new PhoneRiskSnapshot(phoneNormalized, history, riskTier, historyOrders);
    }

    public record PhoneRiskSnapshot(
            String phoneNormalized, PhoneHistory history, RiskTier riskTier, List<Order> orders) {}
}
