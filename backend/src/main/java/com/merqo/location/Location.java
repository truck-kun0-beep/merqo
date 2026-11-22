package com.merqo.location;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.access.LocationType;

/**
 * One place Merqo stocks and sells from: a branch or the warehouse.
 *
 * <p>Responsibility: hold the data of one location. The rules about who may use it live in the
 * access module.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the online fulfilment flag is only changed through the location
 *       service, which also enforces the warehouse rule.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "locations")
public class Location {

    @Id private UUID id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 30)
    private LocationType locationType;

    @Column(name = "address_line", length = 240)
    private String address;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_online_fulfillment", nullable = false)
    private boolean onlineFulfillment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Location() {}

    /**
     * Creates a location.
     *
     * @param id the id
     * @param code the short code
     * @param name the display name
     * @param locationType the kind of location
     * @param createdAt when it was created
     */
    public Location(
            UUID id, String code, String name, LocationType locationType, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.locationType = locationType;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.active = true;
        this.onlineFulfillment = false;
    }

    /**
     * The id of the location.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The short code, for example {@code DM}.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }

    /**
     * Sets the short code.
     *
     * @param code the code
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * The display name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the display name.
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * The kind of location.
     *
     * @return the location type
     */
    public LocationType getLocationType() {
        return locationType;
    }

    /**
     * The address.
     *
     * @return the address
     */
    public String getAddress() {
        return address;
    }

    /**
     * Sets the address.
     *
     * @param address the address
     */
    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * The phone number.
     *
     * @return the phone
     */
    public String getPhone() {
        return phone;
    }

    /**
     * Sets the phone number.
     *
     * @param phone the phone
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    /**
     * Whether the location is in use.
     *
     * @return {@code true} when the location is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates or deactivates the location.
     *
     * @param active the new state
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Whether this location fulfils online orders.
     *
     * @return {@code true} for the online fulfilment location
     */
    public boolean isOnlineFulfillment() {
        return onlineFulfillment;
    }

    /**
     * Sets the online fulfilment flag.
     *
     * @param onlineFulfillment the new state
     */
    public void setOnlineFulfillment(boolean onlineFulfillment) {
        this.onlineFulfillment = onlineFulfillment;
    }

    /**
     * When the location was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the location was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the location was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
