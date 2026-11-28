package com.merqo.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.orders.domain.OrderChannel;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PaymentTerms.ClaimedAdvanceMethod;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;

/** Persistent online or manual order fulfilled at the online warehouse. */
@Entity
@Table(name = "orders")
public class Order {

    @Id private UUID id;

    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private OrderChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 160)
    private String customerName;

    @Column(name = "phone_raw", nullable = false, length = 40)
    private String phoneRaw;

    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "district", length = 80)
    private String district;

    @Column(name = "note")
    private String note;

    @Column(name = "external_order_id", length = 120)
    private String externalOrderId;

    @Column(name = "integration_client_id")
    private UUID integrationClientId;

    @Column(name = "source_label", length = 80)
    private String sourceLabel;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountTotal;

    @Column(name = "shipping_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 30)
    private PaymentMode paymentMode;

    @Column(name = "advance_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal advanceAmount;

    @Column(name = "cod_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal codAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "claimed_advance_method", length = 30)
    private ClaimedAdvanceMethod claimedAdvanceMethod;

    @Column(name = "claimed_advance_reference", length = 120)
    private String claimedAdvanceReference;

    @Column(name = "price_mismatch", nullable = false)
    private boolean priceMismatch;

    @Column(name = "follow_up_at")
    private Instant followUpAt;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "cancellation_request_note")
    private String cancellationRequestNote;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Order() {}

    public Order(
            UUID id,
            String orderNumber,
            OrderChannel channel,
            OrderStatus status,
            UUID locationId,
            UUID customerId,
            String customerName,
            String phoneRaw,
            String phoneNormalized,
            String address,
            String district,
            String note,
            String externalOrderId,
            UUID integrationClientId,
            String sourceLabel,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal shippingFee,
            BigDecimal total,
            PaymentMode paymentMode,
            BigDecimal advanceAmount,
            BigDecimal codAmount,
            ClaimedAdvanceMethod claimedAdvanceMethod,
            String claimedAdvanceReference,
            boolean priceMismatch,
            UUID createdBy,
            String demoKey,
            Instant createdAt) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.channel = channel;
        this.status = status;
        this.locationId = locationId;
        this.customerId = customerId;
        this.customerName = customerName;
        this.phoneRaw = phoneRaw;
        this.phoneNormalized = phoneNormalized;
        this.address = address;
        this.district = district;
        this.note = note;
        this.externalOrderId = externalOrderId;
        this.integrationClientId = integrationClientId;
        this.sourceLabel = sourceLabel;
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.shippingFee = shippingFee;
        this.total = total;
        this.paymentMode = paymentMode;
        this.advanceAmount = advanceAmount;
        this.codAmount = codAmount;
        this.claimedAdvanceMethod = claimedAdvanceMethod;
        this.claimedAdvanceReference = claimedAdvanceReference;
        this.priceMismatch = priceMismatch;
        this.createdBy = createdBy;
        this.demoKey = demoKey;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderChannel getChannel() {
        return channel;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getPhoneRaw() {
        return phoneRaw;
    }

    public void setPhoneRaw(String phoneRaw) {
        this.phoneRaw = phoneRaw;
    }

    public String getPhoneNormalized() {
        return phoneNormalized;
    }

    public void setPhoneNormalized(String phoneNormalized) {
        this.phoneNormalized = phoneNormalized;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getExternalOrderId() {
        return externalOrderId;
    }

    public UUID getIntegrationClientId() {
        return integrationClientId;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public PaymentMode getPaymentMode() {
        return paymentMode;
    }

    public BigDecimal getAdvanceAmount() {
        return advanceAmount;
    }

    public BigDecimal getCodAmount() {
        return codAmount;
    }

    public ClaimedAdvanceMethod getClaimedAdvanceMethod() {
        return claimedAdvanceMethod;
    }

    public String getClaimedAdvanceReference() {
        return claimedAdvanceReference;
    }

    public boolean isPriceMismatch() {
        return priceMismatch;
    }

    public Instant getFollowUpAt() {
        return followUpAt;
    }

    public void setFollowUpAt(Instant followUpAt) {
        this.followUpAt = followUpAt;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public void setCancellationRequestedAt(Instant cancellationRequestedAt) {
        this.cancellationRequestedAt = cancellationRequestedAt;
    }

    public String getCancellationRequestNote() {
        return cancellationRequestNote;
    }

    public void setCancellationRequestNote(String cancellationRequestNote) {
        this.cancellationRequestNote = cancellationRequestNote;
    }

    public UUID getCreatedBy() {
        return createdBy;
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

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }
}
