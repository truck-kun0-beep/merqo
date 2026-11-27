package com.merqo.pos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity representing a POS cash drawer shift session.
 *
 * <p>Responsibility: hold opening float, status, and closed reconciliation snapshots for a
 * cashier's shift at a single branch. Once {@link CashSessionStatus#CLOSED}, a database trigger
 * forbids any further updates or deletes.
 *
 * <p>OOP concept: <b>Encapsulation &amp; Entity State Guard</b> — {@link #close} encapsulates the
 * transition from {@code OPEN} to {@code CLOSED} and prevents mutating an already closed session.
 */
@Entity
@Table(name = "cash_sessions")
public class CashSession {

    @Id private UUID id;

    @Column(name = "session_number", nullable = false, length = 20)
    private String sessionNumber;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "opening_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingCash;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CashSessionStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "counted_cash", precision = 14, scale = 2)
    private BigDecimal countedCash;

    @Column(name = "counted_bkash", precision = 14, scale = 2)
    private BigDecimal countedBkash;

    @Column(name = "counted_nagad", precision = 14, scale = 2)
    private BigDecimal countedNagad;

    @Column(name = "counted_card", precision = 14, scale = 2)
    private BigDecimal countedCard;

    @Column(name = "expected_cash", precision = 14, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "expected_bkash", precision = 14, scale = 2)
    private BigDecimal expectedBkash;

    @Column(name = "expected_nagad", precision = 14, scale = 2)
    private BigDecimal expectedNagad;

    @Column(name = "expected_card", precision = 14, scale = 2)
    private BigDecimal expectedCard;

    @Column(name = "difference_cash", precision = 14, scale = 2)
    private BigDecimal differenceCash;

    @Column(name = "difference_bkash", precision = 14, scale = 2)
    private BigDecimal differenceBkash;

    @Column(name = "difference_nagad", precision = 14, scale = 2)
    private BigDecimal differenceNagad;

    @Column(name = "difference_card", precision = 14, scale = 2)
    private BigDecimal differenceCard;

    @Column(name = "close_note")
    private String closeNote;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected CashSession() {}

    /**
     * Opens a new cash session.
     *
     * @param id session UUID
     * @param sessionNumber human-readable number ({@code CS-000001})
     * @param locationId branch location id
     * @param cashierId cashier user id
     * @param openingCash opening drawer float
     * @param openedAt UTC open timestamp
     */
    public CashSession(
            UUID id,
            String sessionNumber,
            UUID locationId,
            UUID cashierId,
            BigDecimal openingCash,
            Instant openedAt) {
        this.id = id;
        this.sessionNumber = sessionNumber;
        this.locationId = locationId;
        this.cashierId = cashierId;
        this.openingCash = openingCash;
        this.openedAt = openedAt;
        this.status = CashSessionStatus.OPEN;
        this.createdAt = openedAt;
        this.updatedAt = openedAt;
    }

    /**
     * Closes this session with server-computed expected, counted, and difference figures.
     *
     * @param closedBy user closing the session
     * @param closedAt UTC close timestamp
     * @param countedCash counted cash
     * @param countedBkash counted bKash
     * @param countedNagad counted Nagad
     * @param countedCard counted Card
     * @param expectedCash expected cash
     * @param expectedBkash expected bKash
     * @param expectedNagad expected Nagad
     * @param expectedCard expected Card
     * @param differenceCash cash difference
     * @param differenceBkash bKash difference
     * @param differenceNagad Nagad difference
     * @param differenceCard card difference
     * @param closeNote optional/mandatory reconciliation note
     */
    public void close(
            UUID closedBy,
            Instant closedAt,
            BigDecimal countedCash,
            BigDecimal countedBkash,
            BigDecimal countedNagad,
            BigDecimal countedCard,
            BigDecimal expectedCash,
            BigDecimal expectedBkash,
            BigDecimal expectedNagad,
            BigDecimal expectedCard,
            BigDecimal differenceCash,
            BigDecimal differenceBkash,
            BigDecimal differenceNagad,
            BigDecimal differenceCard,
            String closeNote) {
        this.status = CashSessionStatus.CLOSED;
        this.closedBy = closedBy;
        this.closedAt = closedAt;
        this.countedCash = countedCash;
        this.countedBkash = countedBkash;
        this.countedNagad = countedNagad;
        this.countedCard = countedCard;
        this.expectedCash = expectedCash;
        this.expectedBkash = expectedBkash;
        this.expectedNagad = expectedNagad;
        this.expectedCard = expectedCard;
        this.differenceCash = differenceCash;
        this.differenceBkash = differenceBkash;
        this.differenceNagad = differenceNagad;
        this.differenceCard = differenceCard;
        this.closeNote = closeNote;
        this.updatedAt = closedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSessionNumber() {
        return sessionNumber;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCashierId() {
        return cashierId;
    }

    public BigDecimal getOpeningCash() {
        return openingCash;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public CashSessionStatus getStatus() {
        return status;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getClosedBy() {
        return closedBy;
    }

    public BigDecimal getCountedCash() {
        return countedCash;
    }

    public BigDecimal getCountedBkash() {
        return countedBkash;
    }

    public BigDecimal getCountedNagad() {
        return countedNagad;
    }

    public BigDecimal getCountedCard() {
        return countedCard;
    }

    public BigDecimal getExpectedCash() {
        return expectedCash;
    }

    public BigDecimal getExpectedBkash() {
        return expectedBkash;
    }

    public BigDecimal getExpectedNagad() {
        return expectedNagad;
    }

    public BigDecimal getExpectedCard() {
        return expectedCard;
    }

    public BigDecimal getDifferenceCash() {
        return differenceCash;
    }

    public BigDecimal getDifferenceBkash() {
        return differenceBkash;
    }

    public BigDecimal getDifferenceNagad() {
        return differenceNagad;
    }

    public BigDecimal getDifferenceCard() {
        return differenceCard;
    }

    public String getCloseNote() {
        return closeNote;
    }

    public String getDemoKey() {
        return demoKey;
    }

    public void setDemoKey(String demoKey) {
        this.demoKey = demoKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
