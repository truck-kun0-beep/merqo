package com.merqo.settings;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One business setting.
 *
 * <p>Responsibility: hold one setting value. The key, the type and the default live in the {@link
 * SettingKey} enum, so the database only has to store what was written.
 *
 * <p>OOP concept: the enum is the model of the setting; this class is only the storage.
 */
@Entity
@Table(name = "settings")
public class Setting {

    @Id private UUID id;

    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    @Column(name = "value_type", nullable = false, length = 30)
    private String valueType;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Setting() {}

    /**
     * Creates a setting row.
     *
     * @param id the id
     * @param key the setting key
     * @param value the stored value
     * @param valueType the type the value was validated against
     * @param effectiveFrom when the value starts to count
     * @param now the current moment
     */
    public Setting(
            UUID id,
            String key,
            String value,
            String valueType,
            Instant effectiveFrom,
            Instant now) {
        this.id = id;
        this.settingKey = key;
        this.settingValue = value;
        this.valueType = valueType;
        this.effectiveFrom = effectiveFrom;
        this.createdAt = now;
        this.updatedAt = now;
        this.active = true;
    }

    /**
     * The id of the row.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The setting key.
     *
     * @return the key
     */
    public String getSettingKey() {
        return settingKey;
    }

    /**
     * The stored value.
     *
     * @return the value
     */
    public String getSettingValue() {
        return settingValue;
    }

    /**
     * Sets the stored value.
     *
     * @param settingValue the new value
     */
    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }

    /**
     * The type the value was validated against.
     *
     * @return the value type
     */
    public String getValueType() {
        return valueType;
    }

    /**
     * Sets the type.
     *
     * @param valueType the new type
     */
    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    /**
     * When the value starts to count.
     *
     * @return the effective moment
     */
    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    /**
     * When the row was written.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the row was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the row was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
