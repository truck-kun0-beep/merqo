package com.merqo.orders;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds or atomically registers a customer record for a normalized Bangladeshi mobile number. */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public CustomerService(
            CustomerRepository customerRepository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.customerRepository = customerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Resolves an existing customer for {@code normalizedPhone} or inserts one safely under
     * concurrent traffic using {@code ON CONFLICT DO NOTHING}.
     */
    @Transactional
    public Customer findOrCreateByPhone(String normalizedPhone, String latestName) {
        Instant now = Instant.now(clock);
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                """
                INSERT INTO customers (id, normalized_phone, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (normalized_phone) DO NOTHING
                """,
                UUID.randomUUID(),
                normalizedPhone,
                latestName,
                ts,
                ts);

        Customer customer =
                customerRepository
                        .findByNormalizedPhone(normalizedPhone)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Customer disappeared for phone "
                                                        + normalizedPhone));
        if (latestName != null && !latestName.isBlank() && !latestName.equals(customer.getName())) {
            customer.setName(latestName.trim());
            customer.setUpdatedAt(now);
            customerRepository.save(customer);
        }
        return customer;
    }
}
