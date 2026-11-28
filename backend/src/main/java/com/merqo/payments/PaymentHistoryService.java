package com.merqo.payments;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.common.time.BusinessTime;
import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.location.LocationRepository;
import com.merqo.orders.domain.PhoneNumber;
import com.merqo.payments.PaymentDtos.PaymentHistoryRowView;
import com.merqo.payments.PaymentDtos.PaymentSummaryResponse;
import com.merqo.payments.PaymentHistoryRepository.HistoryPageResult;
import com.merqo.payments.PaymentHistoryRepository.SearchCriteria;

/** Read-only application service for scoped unified payment history and summary totals. */
@Service
public class PaymentHistoryService {

    private final PaymentHistoryRepository historyRepository;
    private final LocationRepository locationRepository;

    public PaymentHistoryService(
            PaymentHistoryRepository historyRepository, LocationRepository locationRepository) {
        this.historyRepository = historyRepository;
        this.locationRepository = locationRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentHistoryRowView> history(
            AccessContext context,
            String type,
            String method,
            String status,
            UUID locationId,
            LocalDate fromDate,
            LocalDate toDate,
            String q,
            PageRequest pageRequest) {
        SearchCriteria criteria =
                buildCriteria(context, type, method, status, locationId, fromDate, toDate, q);
        HistoryPageResult result =
                historyRepository.findHistoryPage(criteria, pageRequest.page(), pageRequest.size());
        return PageResponse.of(
                new PageImpl<>(result.rows(), pageRequest.toSpring(), result.totalElements()));
    }

    @Transactional(readOnly = true)
    public PaymentSummaryResponse summary(
            AccessContext context,
            String type,
            String method,
            String status,
            UUID locationId,
            LocalDate fromDate,
            LocalDate toDate,
            String q) {
        SearchCriteria criteria =
                buildCriteria(context, type, method, status, locationId, fromDate, toDate, q);
        return historyRepository.summarize(criteria);
    }

    private SearchCriteria buildCriteria(
            AccessContext context,
            String type,
            String method,
            String status,
            UUID locationId,
            LocalDate fromDate,
            LocalDate toDate,
            String q) {
        Set<UUID> allLocationIds = Set.copyOf(locationRepository.findAllIds());
        Set<UUID> visiblePaymentLocations =
                context.visibleLocationIds(Permission.PAYMENT_VIEW, allLocationIds);
        if (locationId != null) {
            context.requireInScope(locationId);
            context.require(Permission.PAYMENT_VIEW, locationId);
        }
        boolean canManageCod = context.can(Permission.COD_SETTLEMENT_MANAGE);

        Instant fromInstant = fromDate == null ? null : BusinessTime.startOfBusinessDay(fromDate);
        Instant toInstant = toDate == null ? null : BusinessTime.endOfBusinessDay(toDate);

        String trimmedQ = trimToNull(q);
        String qLike = trimmedQ == null ? null : "%" + trimmedQ.toLowerCase(Locale.ROOT) + "%";
        String phoneNormLike = null;
        if (trimmedQ != null) {
            PhoneNumber parsed = PhoneNumber.parse(trimmedQ);
            if (parsed.isValid()) {
                phoneNormLike = "%" + parsed.normalized() + "%";
            } else {
                String digitsOnly = trimmedQ.replaceAll("\\D", "");
                if (digitsOnly.length() >= 4) {
                    phoneNormLike = "%" + digitsOnly + "%";
                }
            }
        }

        return new SearchCriteria(
                visiblePaymentLocations,
                canManageCod,
                upperOrNull(type),
                upperOrNull(method),
                upperOrNull(status),
                locationId,
                fromInstant,
                toInstant,
                qLike,
                phoneNormLike);
    }

    private static String upperOrNull(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
