package com.merqo.transfer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.transfer.domain.TransferStatus;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the stock transfers.
 *
 * <p>Responsibility: translate HTTP into calls on {@link TransferService}.
 *
 * <p>A transfer moves through a fixed set of states, and every step is its own endpoint. The
 * service decides which step is legal, so a caller cannot skip the approval by calling the wrong
 * endpoint.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Stock transfers between locations")
public class TransferController {

    private final TransferService transferService;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param transferService the transfer rules
     * @param currentUserService the signed in user
     */
    public TransferController(
            TransferService transferService, CurrentUserService currentUserService) {
        this.transferService = transferService;
        this.currentUserService = currentUserService;
    }

    /**
     * The transfers the caller may see.
     *
     * @param status the state filter
     * @return the transfers
     */
    @GetMapping
    @Operation(summary = "The transfers the caller may see")
    public List<Map<String, Object>> list(@RequestParam(required = false) TransferStatus status) {
        AccessContext context = currentUserService.requireAccessContext();
        List<Map<String, Object>> items = new ArrayList<>();
        for (StockTransfer transfer : transferService.list(context, status)) {
            items.add(transferService.toSummary(context, transfer));
        }
        return items;
    }

    /**
     * One transfer with its lines and its history.
     *
     * @param id the transfer
     * @return the transfer
     */
    @GetMapping("/{id}")
    @Operation(summary = "One transfer with its lines and its history")
    public Map<String, Object> get(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        StockTransfer transfer = transferService.get(context, id);
        Map<String, Object> body = transferService.toSummary(context, transfer);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (StockTransferItem item : transferService.linesOf(id)) {
            lines.add(transferService.toLine(item));
        }
        body.put("lines", lines);
        List<Map<String, Object>> history = new ArrayList<>();
        for (TransferStatusHistory row : transferService.historyOf(id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(
                    "fromStatus", row.getFromStatus() == null ? null : row.getFromStatus().name());
            entry.put("toStatus", row.getToStatus().name());
            entry.put("actorUsername", row.getActorUsername());
            entry.put("note", row.getNote());
            entry.put("createdAt", row.getCreatedAt());
            history.add(entry);
        }
        body.put("history", history);
        return body;
    }

    /**
     * Asks for a transfer of stock from one location to another.
     *
     * @param request the transfer to ask for
     * @return the new transfer
     */
    @PostMapping
    @Operation(summary = "Ask for a transfer of stock")
    public ResponseEntity<Map<String, Object>> request(
            @Valid @RequestBody RequestTransfer request) {
        AccessContext context = currentUserService.requireAccessContext();
        List<TransferService.TransferLineRequest> lines = new ArrayList<>();
        for (TransferLineRequest line : request.lines()) {
            lines.add(new TransferService.TransferLineRequest(line.variantId(), line.quantity()));
        }
        StockTransfer transfer =
                transferService.request(
                        context,
                        request.sourceLocationId(),
                        request.destinationLocationId(),
                        lines,
                        request.note());
        return ResponseEntity.status(201).body(transferService.toSummary(context, transfer));
    }

    /**
     * Approves a transfer.
     *
     * @param id the transfer
     * @param request the approved numbers
     * @return the approved transfer
     */
    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a transfer")
    public Map<String, Object> approve(
            @PathVariable UUID id, @Valid @RequestBody TransferLinesRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        StockTransfer transfer = transferService.get(context, id);
        StockTransfer updated =
                transferService.approve(context, transfer, toLines(request.lines()));
        return transferService.toSummary(context, updated);
    }

    /**
     * Refuses a transfer.
     *
     * @param id the transfer
     * @param request why it was refused
     * @return the refused transfer
     */
    @PostMapping("/{id}/reject")
    @Operation(summary = "Refuse a transfer")
    public Map<String, Object> reject(
            @PathVariable UUID id, @Valid @RequestBody RejectTransferRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        StockTransfer transfer = transferService.get(context, id);
        StockTransfer updated = transferService.reject(context, transfer, request.note());
        return transferService.toSummary(context, updated);
    }

    /**
     * Sends the stock away. The stock on the shelf of the source drops now.
     *
     * @param id the transfer
     * @param request the numbers that leave
     * @return the dispatched transfer
     */
    @PostMapping("/{id}/dispatch")
    @Operation(summary = "Dispatch a transfer")
    public Map<String, Object> dispatch(
            @PathVariable UUID id, @Valid @RequestBody TransferLinesRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        StockTransfer transfer = transferService.get(context, id);
        StockTransfer updated =
                transferService.dispatch(context, transfer, toLines(request.lines()));
        return transferService.toSummary(context, updated);
    }

    /**
     * Receives the stock at the destination.
     *
     * @param id the transfer
     * @param request the numbers that arrive
     * @return the received transfer
     */
    @PostMapping("/{id}/receive")
    @Operation(summary = "Receive a transfer")
    public Map<String, Object> receive(
            @PathVariable UUID id, @Valid @RequestBody TransferLinesRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        StockTransfer transfer = transferService.get(context, id);
        StockTransfer updated =
                transferService.receive(context, transfer, toLines(request.lines()));
        return transferService.toSummary(context, updated);
    }

    /**
     * Closes a transfer that lost some of its goods.
     *
     * @param id the transfer
     * @param request why it is closed
     * @return the closed transfer
     */
    @PostMapping("/{id}/close")
    @Operation(summary = "Close a transfer with a shortage")
    public Map<String, Object> closeWithShortage(
            @PathVariable UUID id, @Valid @RequestBody RejectTransferRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        StockTransfer transfer = transferService.get(context, id);
        StockTransfer updated =
                transferService.closeWithShortage(context, transfer, request.note());
        return transferService.toSummary(context, updated);
    }

    /**
     * Calls a transfer off before anything was sent.
     *
     * @param id the transfer
     * @return the cancelled transfer
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a transfer")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        StockTransfer transfer = transferService.get(context, id);
        StockTransfer updated = transferService.cancel(context, transfer);
        return transferService.toSummary(context, updated);
    }

    private List<TransferService.TransferLineApproval> toLines(List<TransferLineRequest> lines) {
        List<TransferService.TransferLineApproval> result = new ArrayList<>();
        for (TransferLineRequest line : lines) {
            result.add(new TransferService.TransferLineApproval(line.variantId(), line.quantity()));
        }
        return result;
    }

    /**
     * The body of a request transfer request.
     *
     * @param sourceLocationId where the stock comes from
     * @param destinationLocationId where the stock goes
     * @param lines the lines
     * @param note the note, may be {@code null}
     */
    public record RequestTransfer(
            @NotNull UUID sourceLocationId,
            @NotNull UUID destinationLocationId,
            @NotEmpty List<@Valid TransferLineRequest> lines,
            String note) {}

    /**
     * The body of a step of a transfer.
     *
     * @param lines the lines with their numbers
     */
    public record TransferLinesRequest(@NotEmpty List<@Valid TransferLineRequest> lines) {}

    /**
     * One line of a transfer.
     *
     * @param variantId the variant
     * @param quantity how many pieces
     */
    public record TransferLineRequest(@NotNull UUID variantId, @Positive int quantity) {}

    /**
     * The body of a refuse or close request.
     *
     * @param note why
     */
    public record RejectTransferRequest(String note) {}
}
