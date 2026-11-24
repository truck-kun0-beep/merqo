package com.merqo.transfer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.VariantCostHistory;
import com.merqo.catalog.VariantCostHistoryRepository;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.transfer.domain.InvalidTransitionException;
import com.merqo.transfer.domain.TransferLine;
import com.merqo.transfer.domain.TransferStatus;
import com.merqo.transfer.domain.TransferTransitions;

/**
 * The rules of the stock transfers.
 *
 * <p>Responsibility: move stock from one location to another, one step at a time, and keep the
 * state machine honest.
 *
 * <p>The rules that matter:
 *
 * <ol>
 *   <li><b>The stock moves only at dispatch and at receive.</b> A request and an approval move
 *       nothing at all. The goods that are on the way are derived from the lines and are never
 *       stored as stock anywhere.
 *   <li><b>The state machine is one table.</b> {@link TransferTransitions} decides which step may
 *       follow which, so a double submit of a dispatch is answered with a 409 and never moves the
 *       stock twice.
 *   <li><b>The balances are locked in one order.</b> The lines are always sorted by the variant, so
 *       two transfers that run at the same moment cannot deadlock.
 *   <li><b>The requester never sees the stock of the source.</b> The answer of a transfer holds the
 *       stock on hand of the source only for a caller who holds INVENTORY_VIEW there.
 * </ol>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the transfer rules live here, not in the controller.
 *   <li><b>State pattern as a table</b> - the transitions live in one plain Java class.
 *   <li><b>Dependency injection</b> - the repositories, the inventory and the clock are injected.
 *   <li><b>Fail fast</b> - an impossible step throws instead of writing half of it.
 * </ul>
 */
@Service
public class TransferService {

    private final StockTransferRepository transferRepository;
    private final StockTransferItemRepository itemRepository;
    private final TransferStatusHistoryRepository historyRepository;
    private final ProductVariantRepository variantRepository;
    private final VariantCostHistoryRepository costHistoryRepository;
    private final LocationRepository locationRepository;
    private final InventoryService inventoryService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param transferRepository the transfers
     * @param itemRepository the lines of the transfers
     * @param historyRepository the status history
     * @param variantRepository the variants
     * @param costHistoryRepository the cost history
     * @param locationRepository the locations
     * @param inventoryService the one place where the stock changes
     * @param auditService the audit log
     * @param jdbcTemplate the tool that reads the number sequences
     * @param clock the clock
     */
    public TransferService(
            StockTransferRepository transferRepository,
            StockTransferItemRepository itemRepository,
            TransferStatusHistoryRepository historyRepository,
            ProductVariantRepository variantRepository,
            VariantCostHistoryRepository costHistoryRepository,
            LocationRepository locationRepository,
            InventoryService inventoryService,
            AuditService auditService,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.transferRepository = transferRepository;
        this.itemRepository = itemRepository;
        this.historyRepository = historyRepository;
        this.variantRepository = variantRepository;
        this.costHistoryRepository = costHistoryRepository;
        this.locationRepository = locationRepository;
        this.inventoryService = inventoryService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    // ---- Reads --------------------------------------------------------------

    /**
     * The transfers the caller may see.
     *
     * @param context the caller
     * @param status the state filter, may be {@code null}
     * @return the transfers
     */
    @Transactional(readOnly = true)
    public List<StockTransfer> list(AccessContext context, TransferStatus status) {
        Set<UUID> visible = context.visibleLocationIds(Permission.TRANSFER_VIEW);
        if (visible.isEmpty()) {
            return List.of();
        }
        return transferRepository.findVisible(new ArrayList<>(visible), status);
    }

    /**
     * One transfer, when the caller may see it.
     *
     * <p>A transfer is visible to anybody with TRANSFER_VIEW at its source or at its destination.
     * Everything else is a 404, so the caller cannot even learn that it exists.
     *
     * @param context the caller
     * @param transferId the transfer
     * @return the transfer
     */
    @Transactional(readOnly = true)
    public StockTransfer get(AccessContext context, UUID transferId) {
        StockTransfer transfer =
                transferRepository
                        .findById(transferId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!maySee(context, transfer)) {
            throw new NotFoundException("The requested record was not found.");
        }
        return transfer;
    }

    /**
     * The lines of a transfer.
     *
     * @param transferId the transfer
     * @return the lines
     */
    @Transactional(readOnly = true)
    public List<StockTransferItem> linesOf(UUID transferId) {
        return itemRepository.findByTransferIdOrderByVariantIdAsc(transferId);
    }

    /**
     * The status history of a transfer.
     *
     * @param transferId the transfer
     * @return the rows, oldest first
     */
    @Transactional(readOnly = true)
    public List<TransferStatusHistory> historyOf(UUID transferId) {
        return historyRepository.findByTransferIdOrderByCreatedAtAsc(transferId);
    }

    // ---- Request ------------------------------------------------------------

    /**
     * Asks for a transfer of stock from one location to another.
     *
     * <p>The requester picks the variants and the numbers. The requester never sees the stock of
     * the source, and the request itself moves nothing.
     *
     * @param context the caller
     * @param sourceLocationId where the stock comes from
     * @param destinationLocationId where the stock goes
     * @param lines the lines, each with a variant and a quantity
     * @param note the note, may be {@code null}
     * @return the new transfer
     */
    @Transactional
    public StockTransfer request(
            AccessContext context,
            UUID sourceLocationId,
            UUID destinationLocationId,
            List<TransferLineRequest> lines,
            String note) {

        context.require(Permission.TRANSFER_REQUEST, destinationLocationId);
        if (sourceLocationId.equals(destinationLocationId)) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "The source and the destination must be two different places.");
        }
        Location source = requireActiveLocation(sourceLocationId);
        Location destination = requireActiveLocation(destinationLocationId);
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "A transfer needs at least one line.");
        }

        Instant now = Instant.now(clock);
        StockTransfer transfer =
                new StockTransfer(
                        UUID.randomUUID(),
                        nextTransferNumber(),
                        source.getId(),
                        destination.getId(),
                        context.getUserId(),
                        now);
        transfer.setNote(note == null || note.isBlank() ? null : note.trim());
        transferRepository.save(transfer);

        List<TransferLineRequest> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(TransferLineRequest::variantId));
        for (TransferLineRequest line : sorted) {
            if (!variantRepository.existsById(line.variantId())) {
                throw new NotFoundException("The requested record was not found.");
            }
            if (line.quantity() <= 0) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED, "A transfer line needs a positive quantity.");
            }
            itemRepository.save(
                    new StockTransferItem(
                            UUID.randomUUID(),
                            transfer.getId(),
                            line.variantId(),
                            line.quantity()));
        }

        writeHistory(transfer, null, TransferStatus.REQUESTED, context, transfer.getNote());
        auditService.record(
                new AuditEvent.Builder(AuditAction.TRANSFER_REQUESTED, "stock_transfers")
                        .entityId(transfer.getId())
                        .locationId(destination.getId())
                        .actor(context.getUserId(), context.getUsername())
                        .added("number", transfer.getNumber())
                        .added("sourceLocationId", source.getId().toString())
                        .added("destinationLocationId", destination.getId().toString())
                        .added("lineCount", lines.size())
                        .build());
        return transfer;
    }

    // ---- Approve and reject -------------------------------------------------

    /**
     * Approves a transfer. The approver may lower the number of every line.
     *
     * <p>The approval does not reserve and does not move any stock.
     *
     * @param context the caller
     * @param transfer the transfer
     * @param lines the approved numbers, one per line
     * @return the approved transfer
     */
    @Transactional
    public StockTransfer approve(
            AccessContext context, StockTransfer transfer, List<TransferLineApproval> lines) {
        context.require(Permission.TRANSFER_APPROVE, transfer.getSourceLocationId());
        requireStatus(transfer, TransferStatus.REQUESTED);

        Map<UUID, Integer> approved = new LinkedHashMap<>();
        for (TransferLineApproval approval : lines) {
            approved.put(approval.variantId(), approval.quantity());
        }
        for (StockTransferItem item :
                itemRepository.findByTransferIdOrderByVariantIdAsc(transfer.getId())) {
            Integer quantity = approved.get(item.getVariantId());
            if (quantity == null) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "Every line of the transfer needs an approved quantity.");
            }
            if (quantity < 0 || quantity > item.getRequestedQuantity()) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "The approved quantity must not be more than the requested quantity.");
            }
            item.setApprovedQuantity(quantity);
            itemRepository.save(item);
        }

        transfer.setStatus(TransferStatus.APPROVED);
        transfer.markApproved(context.getUserId(), Instant.now(clock));
        transfer.setUpdatedAt(Instant.now(clock));
        transferRepository.save(transfer);

        writeHistory(transfer, TransferStatus.REQUESTED, TransferStatus.APPROVED, context, null);
        auditService.record(
                new AuditEvent.Builder(AuditAction.TRANSFER_APPROVED, "stock_transfers")
                        .entityId(transfer.getId())
                        .locationId(transfer.getSourceLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed(
                                "status",
                                TransferStatus.REQUESTED.name(),
                                TransferStatus.APPROVED.name())
                        .build());
        return transfer;
    }

    /**
     * Refuses a transfer.
     *
     * @param context the caller
     * @param transfer the transfer
     * @param note why it was refused
     * @return the refused transfer
     */
    @Transactional
    public StockTransfer reject(AccessContext context, StockTransfer transfer, String note) {
        context.require(Permission.TRANSFER_APPROVE, transfer.getSourceLocationId());
        requireStatus(transfer, TransferStatus.REQUESTED);

        transfer.setStatus(TransferStatus.REJECTED);
        transfer.setUpdatedAt(Instant.now(clock));
        transferRepository.save(transfer);

        writeHistory(transfer, TransferStatus.REQUESTED, TransferStatus.REJECTED, context, note);
        auditService.record(
                new AuditEvent.Builder(AuditAction.TRANSFER_REJECTED, "stock_transfers")
                        .entityId(transfer.getId())
                        .locationId(transfer.getSourceLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed(
                                "status",
                                TransferStatus.REQUESTED.name(),
                                TransferStatus.REJECTED.name())
                        .added("note", note)
                        .build());
        return transfer;
    }

    // ---- Dispatch and receive -----------------------------------------------

    /**
     * Sends the stock away. The stock on the shelf of the source drops now.
     *
     * @param context the caller
     * @param transfer the transfer
     * @param lines the numbers that leave, one per line
     * @return the dispatched transfer
     */
    @Transactional
    public StockTransfer dispatch(
            AccessContext context, StockTransfer transfer, List<TransferLineApproval> lines) {
        context.require(Permission.TRANSFER_DISPATCH, transfer.getSourceLocationId());
        requireStatus(transfer, TransferStatus.APPROVED);

        Map<UUID, Integer> dispatched = new LinkedHashMap<>();
        for (TransferLineApproval approval : lines) {
            dispatched.put(approval.variantId(), approval.quantity());
        }

        List<StockTransferItem> items =
                itemRepository.findByTransferIdOrderByVariantIdAsc(transfer.getId());
        // The lines are sorted by the variant, so the balances are always locked in the same order.
        List<StockTransferItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(StockTransferItem::getVariantId));

        for (StockTransferItem item : sorted) {
            Integer quantity = dispatched.get(item.getVariantId());
            if (quantity == null) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "Every line of the transfer needs a dispatched quantity.");
            }
            int approved = item.getApprovedQuantity() == null ? 0 : item.getApprovedQuantity();
            if (quantity < 0 || quantity > approved) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "The dispatched quantity must not be more than the approved quantity.");
            }
            if (quantity == 0) {
                continue;
            }
            inventoryService.apply(
                    new InventoryCommand(
                            transfer.getSourceLocationId(),
                            item.getVariantId(),
                            -quantity,
                            MovementType.TRANSFER_OUT,
                            "STOCK_TRANSFER",
                            transfer.getId(),
                            null,
                            transfer.getNote(),
                            currentCost(item.getVariantId()),
                            context.getUserId(),
                            null));
            item.setDispatchedQuantity(quantity);
            itemRepository.save(item);
        }

        transfer.setStatus(TransferStatus.DISPATCHED);
        transfer.markDispatched(context.getUserId(), Instant.now(clock));
        transfer.setUpdatedAt(Instant.now(clock));
        transferRepository.save(transfer);

        writeHistory(transfer, TransferStatus.APPROVED, TransferStatus.DISPATCHED, context, null);
        auditService.record(
                new AuditEvent.Builder(AuditAction.TRANSFER_DISPATCHED, "stock_transfers")
                        .entityId(transfer.getId())
                        .locationId(transfer.getSourceLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed(
                                "status",
                                TransferStatus.APPROVED.name(),
                                TransferStatus.DISPATCHED.name())
                        .build());
        return transfer;
    }

    /**
     * Receives the stock at the destination. The stock on the shelf of the destination rises now.
     *
     * @param context the caller
     * @param transfer the transfer
     * @param lines the numbers that arrive, one per line
     * @return the transfer, received or partly received
     */
    @Transactional
    public StockTransfer receive(
            AccessContext context, StockTransfer transfer, List<TransferLineApproval> lines) {
        context.require(Permission.TRANSFER_RECEIVE, transfer.getDestinationLocationId());
        if (transfer.getStatus() != TransferStatus.DISPATCHED
                && transfer.getStatus() != TransferStatus.PARTIALLY_RECEIVED) {
            throw new ConflictException(
                    ErrorCodes.INVALID_TRANSITION,
                    "A transfer cannot be received from the state " + transfer.getStatus() + ".");
        }

        Map<UUID, Integer> received = new LinkedHashMap<>();
        for (TransferLineApproval approval : lines) {
            received.put(approval.variantId(), approval.quantity());
        }

        List<StockTransferItem> items =
                itemRepository.findByTransferIdOrderByVariantIdAsc(transfer.getId());
        List<StockTransferItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(StockTransferItem::getVariantId));

        boolean everythingArrived = true;
        for (StockTransferItem item : sorted) {
            Integer quantity = received.get(item.getVariantId());
            if (quantity == null) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "Every line of the transfer needs a received quantity.");
            }
            TransferLine line = toDomain(item);
            int outstanding = line.getOutstandingToReceive();
            if (quantity < 0 || quantity > outstanding) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED,
                        "The received quantity must not be more than the outstanding quantity.");
            }
            if (quantity > 0) {
                inventoryService.apply(
                        new InventoryCommand(
                                transfer.getDestinationLocationId(),
                                item.getVariantId(),
                                quantity,
                                MovementType.TRANSFER_IN,
                                "STOCK_TRANSFER",
                                transfer.getId(),
                                null,
                                transfer.getNote(),
                                currentCost(item.getVariantId()),
                                context.getUserId(),
                                null));
                item.setReceivedQuantity(item.getReceivedQuantity() + quantity);
                itemRepository.save(item);
            }
            if (item.getReceivedQuantity() < item.getDispatchedQuantity()) {
                everythingArrived = false;
            }
        }

        TransferStatus target =
                everythingArrived ? TransferStatus.RECEIVED : TransferStatus.PARTIALLY_RECEIVED;
        TransferStatus from = transfer.getStatus();
        transfer.setStatus(target);
        transfer.markReceived(context.getUserId(), Instant.now(clock));
        transfer.setUpdatedAt(Instant.now(clock));
        transferRepository.save(transfer);

        writeHistory(transfer, from, target, context, null);
        auditService.record(
                new AuditEvent.Builder(AuditAction.TRANSFER_RECEIVED, "stock_transfers")
                        .entityId(transfer.getId())
                        .locationId(transfer.getDestinationLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("status", from.name(), target.name())
                        .build());
        return transfer;
    }

    /**
     * Closes a transfer with a shortage. The goods that never arrived are not anywhere.
     *
     * @param context the caller
     * @param transfer the transfer
     * @param note why it was closed, and it is mandatory
     * @return the closed transfer
     */
    @Transactional
    public StockTransfer closeWithShortage(
            AccessContext context, StockTransfer transfer, String note) {
        context.require(Permission.TRANSFER_APPROVE, transfer.getSourceLocationId());
        requireStatus(transfer, TransferStatus.DISPATCHED, TransferStatus.PARTIALLY_RECEIVED);
        if (note == null || note.isBlank()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "A shortage needs a note that says what happened.");
        }

        BigDecimal valueAtCost = BigDecimal.ZERO;
        for (StockTransferItem item :
                itemRepository.findByTransferIdOrderByVariantIdAsc(transfer.getId())) {
            TransferLine line = toDomain(item);
            int shortage = line.getOutstandingToReceive();
            if (shortage > 0) {
                item.setShortageQuantity(shortage);
                itemRepository.save(item);
                BigDecimal cost = currentCost(item.getVariantId());
                if (cost != null) {
                    valueAtCost = valueAtCost.add(cost.multiply(BigDecimal.valueOf(shortage)));
                }
            }
        }

        TransferStatus from = transfer.getStatus();
        transfer.setStatus(TransferStatus.CLOSED_WITH_SHORTAGE);
        transfer.markClosed(context.getUserId(), Instant.now(clock));
        transfer.setUpdatedAt(Instant.now(clock));
        transferRepository.save(transfer);

        writeHistory(transfer, from, TransferStatus.CLOSED_WITH_SHORTAGE, context, note);
        auditService.record(
                new AuditEvent.Builder(AuditAction.TRANSFER_CLOSED_WITH_SHORTAGE, "stock_transfers")
                        .entityId(transfer.getId())
                        .locationId(transfer.getSourceLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("status", from.name(), TransferStatus.CLOSED_WITH_SHORTAGE.name())
                        .added("note", note)
                        .added(
                                "valueAtCost",
                                valueAtCost.setScale(2, java.math.RoundingMode.HALF_UP))
                        .build());
        return transfer;
    }

    /**
     * Calls off a transfer before anything was dispatched.
     *
     * @param context the caller
     * @param transfer the transfer
     * @return the cancelled transfer
     */
    @Transactional
    public StockTransfer cancel(AccessContext context, StockTransfer transfer) {
        if (transfer.getStatus() != TransferStatus.REQUESTED
                && transfer.getStatus() != TransferStatus.APPROVED) {
            throw new ConflictException(
                    ErrorCodes.INVALID_TRANSITION,
                    "A transfer cannot be called off from the state " + transfer.getStatus() + ".");
        }
        boolean isRequester =
                transfer.getRequestedBy() != null
                        && transfer.getRequestedBy().equals(context.getUserId());
        if (!isRequester) {
            context.require(Permission.TRANSFER_APPROVE, transfer.getSourceLocationId());
        }

        TransferStatus from = transfer.getStatus();
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setUpdatedAt(Instant.now(clock));
        transferRepository.save(transfer);

        writeHistory(transfer, from, TransferStatus.CANCELLED, context, null);
        auditService.record(
                new AuditEvent.Builder(AuditAction.TRANSFER_CANCELLED, "stock_transfers")
                        .entityId(transfer.getId())
                        .locationId(transfer.getDestinationLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("status", from.name(), TransferStatus.CANCELLED.name())
                        .build());
        return transfer;
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * The answer of one transfer, as the interface shows it.
     *
     * @param context the caller
     * @param transfer the transfer
     * @return the map
     */
    public Map<String, Object> toSummary(AccessContext context, StockTransfer transfer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", transfer.getId());
        body.put("number", transfer.getNumber());
        body.put("sourceLocationId", transfer.getSourceLocationId());
        body.put("destinationLocationId", transfer.getDestinationLocationId());
        body.put("status", transfer.getStatus().name());
        body.put("note", transfer.getNote());
        body.put("requestedAt", transfer.getRequestedAt());
        body.put("approvedAt", transfer.getApprovedAt());
        body.put("dispatchedAt", transfer.getDispatchedAt());
        body.put("receivedAt", transfer.getReceivedAt());
        body.put("closedAt", transfer.getClosedAt());
        return body;
    }

    /**
     * The answer of one line of a transfer.
     *
     * @param item the line
     * @return the map
     */
    public Map<String, Object> toLine(StockTransferItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", item.getId());
        body.put("variantId", item.getVariantId());
        body.put("requestedQuantity", item.getRequestedQuantity());
        body.put("approvedQuantity", item.getApprovedQuantity());
        body.put("dispatchedQuantity", item.getDispatchedQuantity());
        body.put("receivedQuantity", item.getReceivedQuantity());
        body.put("shortageQuantity", item.getShortageQuantity());
        body.put("inTransit", inTransit(item));
        return body;
    }

    /**
     * The goods that are on the way for one line.
     *
     * @param item the line
     * @return the number of pieces on the way
     */
    public static int inTransit(StockTransferItem item) {
        return item.getDispatchedQuantity()
                - item.getReceivedQuantity()
                - item.getShortageQuantity();
    }

    /**
     * The domain view of a line.
     *
     * @param item the line
     * @return the domain line
     */
    private TransferLine toDomain(StockTransferItem item) {
        return new TransferLine(
                item.getRequestedQuantity(),
                item.getApprovedQuantity() == null ? 0 : item.getApprovedQuantity(),
                item.getDispatchedQuantity(),
                item.getReceivedQuantity(),
                item.getShortageQuantity());
    }

    private boolean maySee(AccessContext context, StockTransfer transfer) {
        return context.visibleLocationIds(Permission.TRANSFER_VIEW)
                        .contains(transfer.getSourceLocationId())
                || context.visibleLocationIds(Permission.TRANSFER_VIEW)
                        .contains(transfer.getDestinationLocationId());
    }

    private void requireStatus(StockTransfer transfer, TransferStatus expected) {
        try {
            TransferTransitions.require(transfer.getStatus(), expected);
        } catch (InvalidTransitionException exception) {
            throw new ConflictException(ErrorCodes.INVALID_TRANSITION, exception.getMessage());
        }
    }

    private void requireStatus(
            StockTransfer transfer, TransferStatus first, TransferStatus second) {
        if (transfer.getStatus() != first && transfer.getStatus() != second) {
            throw new ConflictException(
                    ErrorCodes.INVALID_TRANSITION,
                    "A transfer cannot change from the state " + transfer.getStatus() + ".");
        }
    }

    private Location requireActiveLocation(UUID locationId) {
        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!location.isActive()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "An inactive location may not be used.");
        }
        return location;
    }

    private BigDecimal currentCost(UUID variantId) {
        return costHistoryRepository
                .findLatest(variantId)
                .map(VariantCostHistory::getValue)
                .orElse(null);
    }

    private void writeHistory(
            StockTransfer transfer,
            TransferStatus from,
            TransferStatus to,
            AccessContext context,
            String note) {
        historyRepository.save(
                new TransferStatusHistory(
                        UUID.randomUUID(),
                        transfer.getId(),
                        from,
                        to,
                        context.getUserId(),
                        context.getUsername(),
                        note,
                        Instant.now(clock)));
    }

    private String nextTransferNumber() {
        Long value =
                jdbcTemplate.queryForObject(
                        "select nextval('transfer_number_sequence')", Long.class);
        return String.format("TRF-%06d", value == null ? 1L : value);
    }

    /**
     * One line of a transfer request.
     *
     * @param variantId the variant
     * @param quantity how many pieces
     */
    public record TransferLineRequest(UUID variantId, int quantity) {}

    /**
     * One line of a transfer step.
     *
     * @param variantId the variant
     * @param quantity how many pieces
     */
    public record TransferLineApproval(UUID variantId, int quantity) {}
}
