package com.merqo.orders;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for {@link Customer}. */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByNormalizedPhone(String normalizedPhone);
}
