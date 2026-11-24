package com.merqo.inventory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.CorrelationIdFilter;
import com.merqo.inventory.domain.InsufficientStockException;
import com.merqo.inventory.domain.InventoryBalance;

/**
 * The one place where the stock of Merqo changes.
 *
 * <p>Responsibility: change the balance of one variant at one location, write the movement that
 * describes the change and keep the invariants of the balance.
 *
 * <p>The three rules that make the stock trustworthy:
 *
 * <ol>
 *   <li><b>One movement per change.</b> Every change of the stock on the shelf writes exactly one
 *       row of the append only movement table, in the same transaction. The sum of the deltas of a
 *       balance is therefore always its stock on the shelf.
 *   <li><b>The balance row is locked.</b> The row is read with a pessimistic lock, so two requests
 *       that touch the same stock wait for each other instead of overwriting each other. That is
 *       what stops two cashiers from selling the same last piece.
 *   <li><b>The rules are the plain Java ones.</b> The balance is turned into an {@link
 *       InventoryBalance}, the change is applied there, and the result is written back. The
 *       invariants can never be broken by a caller.
 * </ol>
 *
 * <p>A reservation is not a physical change, so it writes no movement. The stock stays on the shelf
 * and only the promise changes.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class is the only place that writes balances and
 *       movements.
 *   <li><b>Dependency injection</b> - the repositories and the clock are injected.
 *   <li><b>Facade</b> - the callers ask for "receive five pieces" and never see the balance row.
 *   <li><b>Fail fast</b> - a change that would break an invariant throws instead of writing half of
 *       it.
 * </ul>
 */
@Service
public class InventoryLedger implements InventoryService {

    private final StockBalanceRepository balanceRepository;
    private final StockMovementRepository movementRepository;
    private final Clock clock;

    /**
     * Creates the ledger.
     *
     * @param balanceRepository the balances
     * @param movementRepository the movements
     * @param clock the clock
     */
    public InventoryLedger(
            StockBalanceRepository balanceRepository,
            StockMovementRepository movementRepository,
            Clock clock) {
        this.balanceRepository = balanceRepository;
        this.movementRepository = movementRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int receive(InventoryCommand command) {
        return apply(command);
    }

    @Override
    @Transactional
    public int remove(InventoryCommand command) {
        return apply(command);
    }

    @Override
    @Transactional
    public int apply(InventoryCommand command) {
        StockBalance balance = lockOrCreate(command.locationId(), command.variantId());
        InventoryBalance current = toDomain(balance);
        InventoryBalance updated;
        if (command.isInbound()) {
            updated = current.receive(command.quantity());
        } else {
            try {
                updated = current.remove(command.quantity());
            } catch (InsufficientStockException exception) {
                throw new ConflictException(ErrorCodes.INSUFFICIENT_STOCK, exception.getMessage());
            }
        }
        write(balance, updated, command, command.quantityDelta());
        return updated.getOnHand();
    }

    @Override
    @Transactional
    public void reserve(InventoryCommand command) {
        StockBalance balance = lockOrCreate(command.locationId(), command.variantId());
        InventoryBalance current = toDomain(balance);
        try {
            InventoryBalance updated = current.reserve(command.quantity());
            balance.apply(
                    updated.getOnHand(),
                    updated.getReserved(),
                    updated.getInInspection(),
                    Instant.now(clock));
            balanceRepository.save(balance);
        } catch (InsufficientStockException exception) {
            throw new ConflictException(ErrorCodes.INSUFFICIENT_STOCK, exception.getMessage());
        }
    }

    @Override
    @Transactional
    public void release(InventoryCommand command) {
        StockBalance balance = lockOrCreate(command.locationId(), command.variantId());
        InventoryBalance current = toDomain(balance);
        InventoryBalance updated = current.release(command.quantity());
        balance.apply(
                updated.getOnHand(),
                updated.getReserved(),
                updated.getInInspection(),
                Instant.now(clock));
        balanceRepository.save(balance);
    }

    @Override
    @Transactional
    public int fulfillReservation(InventoryCommand command) {
        StockBalance balance = lockOrCreate(command.locationId(), command.variantId());
        InventoryBalance current = toDomain(balance);
        try {
            InventoryBalance updated = current.fulfillReservation(command.quantity());
            write(balance, updated, command, -command.quantity());
            return updated.getOnHand();
        } catch (InsufficientStockException exception) {
            throw new ConflictException(ErrorCodes.INSUFFICIENT_STOCK, exception.getMessage());
        }
    }

    @Override
    @Transactional
    public int moveToInspection(InventoryCommand command) {
        StockBalance balance = lockOrCreate(command.locationId(), command.variantId());
        InventoryBalance current = toDomain(balance);
        try {
            InventoryBalance updated = current.moveToInspection(command.quantity());
            write(balance, updated, command, -command.quantity());
            return updated.getOnHand();
        } catch (InsufficientStockException exception) {
            throw new ConflictException(ErrorCodes.INSUFFICIENT_STOCK, exception.getMessage());
        }
    }

    @Override
    @Transactional
    public int resolveInspection(InventoryCommand command, int accepted) {
        StockBalance balance = lockOrCreate(command.locationId(), command.variantId());
        InventoryBalance current = toDomain(balance);
        InventoryBalance updated = current.resolveInspection(command.quantity(), accepted);
        write(balance, updated, command, accepted);
        return updated.getOnHand();
    }

    @Override
    @Transactional(readOnly = true)
    public StockBalance balanceOf(UUID locationId, UUID variantId) {
        return balanceRepository.findByLocationIdAndVariantId(locationId, variantId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public long countMovementsAt(UUID locationId, UUID variantId) {
        return movementRepository.countMovements(locationId, variantId);
    }

    /**
     * The balance row of a variant at a location, locked for writing.
     *
     * <p>The row is created when it does not exist yet. The first movement of a variant at a
     * location therefore makes the balance appear.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the locked balance
     */
    private StockBalance lockOrCreate(UUID locationId, UUID variantId) {
        Optional<StockBalance> existing = balanceRepository.findForUpdate(locationId, variantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        StockBalance created =
                new StockBalance(UUID.randomUUID(), locationId, variantId, Instant.now(clock));
        return balanceRepository.saveAndFlush(created);
    }

    /**
     * The domain view of a balance row.
     *
     * @param balance the row
     * @return the domain balance
     */
    private InventoryBalance toDomain(StockBalance balance) {
        return new InventoryBalance(
                balance.getOnHand(), balance.getReserved(), balance.getInInspection());
    }

    /**
     * Writes the new balance and the movement that describes the change.
     *
     * @param balance the balance row
     * @param updated the new balance
     * @param command the change
     * @param signedDelta the signed change of the stock on the shelf
     */
    private void write(
            StockBalance balance,
            InventoryBalance updated,
            InventoryCommand command,
            int signedDelta) {
        Instant now = Instant.now(clock);
        balance.apply(updated.getOnHand(), updated.getReserved(), updated.getInInspection(), now);
        balanceRepository.save(balance);

        movementRepository.save(
                new StockMovement(
                        UUID.randomUUID(),
                        command.locationId(),
                        command.variantId(),
                        command.movementType(),
                        signedDelta,
                        updated.getOnHand(),
                        command.referenceType(),
                        command.referenceId(),
                        command.reason(),
                        command.note(),
                        command.unitCost(),
                        command.actorUserId(),
                        now,
                        correlationId(command)));
    }

    /**
     * The id of the request, taken from the log context when the caller did not give one.
     *
     * @param command the change
     * @return the correlation id, or {@code null}
     */
    private String correlationId(InventoryCommand command) {
        if (command.correlationId() != null) {
            return command.correlationId();
        }
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
