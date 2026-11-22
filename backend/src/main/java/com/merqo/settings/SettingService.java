package com.merqo.settings;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.common.error.ValidationException;

/**
 * Reads and writes the business settings.
 *
 * <p>Responsibility: turn the typed {@link SettingKey} enum into rows and back. Reading a key that
 * was never written returns the default from the enum, so the caller never has to know whether a
 * value exists.
 *
 * <p>A batch write is all or nothing: every value is validated first, and only when all of them are
 * valid is anything written.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Facade</b> - the callers ask for a typed value and never see the storage.
 *   <li><b>Strategy</b> - the validation lives in the enum, so every key validates its own value.
 *   <li><b>Single responsibility</b> - reading and writing settings is all this class does.
 * </ul>
 */
@Service
public class SettingService {

    private final SettingRepository settingRepository;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param settingRepository the settings
     * @param clock the clock
     */
    public SettingService(SettingRepository settingRepository, Clock clock) {
        this.settingRepository = settingRepository;
        this.clock = clock;
    }

    /**
     * The raw value of one key, or the default when it was never written.
     *
     * @param key the key
     * @return the value
     */
    @Transactional(readOnly = true)
    public String getString(SettingKey key) {
        return settingRepository
                .findBySettingKey(key.getKey())
                .map(Setting::getSettingValue)
                .orElse(key.getDefaultValue());
    }

    /**
     * The typed value of one key, or the default when it was never written.
     *
     * @param key the key
     * @param type the expected type
     * @param <T> the type
     * @return the value
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public <T> T get(SettingKey key, Class<T> type) {
        return (T) key.parse(getString(key));
    }

    /**
     * Every setting with its current value, grouped for the settings screen.
     *
     * @return the settings
     */
    @Transactional(readOnly = true)
    public Map<SettingKey.Group, List<Map<String, Object>>> allGrouped() {
        Map<SettingKey.Group, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        // Every group starts with an empty, growable list. An immutable list cannot be added to,
        // and the entries are filled in below.
        for (SettingKey.Group group : SettingKey.Group.values()) {
            grouped.put(group, new ArrayList<>());
        }
        for (SettingKey key : SettingKey.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key.getKey());
            entry.put("label", key.getLabel());
            entry.put("type", key.getType().name());
            entry.put("value", getString(key));
            entry.put("defaultValue", key.getDefaultValue());
            entry.put("readOnly", SettingKey.readOnlyKeys().contains(key.getKey()));
            grouped.get(key.getGroup()).add(entry);
        }
        return grouped;
    }

    /**
     * Writes a batch of settings. Every value is validated before anything is written.
     *
     * @param values the key and value pairs
     * @return the values that were written
     */
    @Transactional
    public Map<String, String> writeAll(Map<String, String> values) {
        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            SettingKey key = SettingKey.fromKey(entry.getKey());
            if (key == null) {
                throw new ValidationException(
                        "VALIDATION_FAILED", "Unknown setting: " + entry.getKey());
            }
            if (SettingKey.readOnlyKeys().contains(key.getKey())) {
                throw new ValidationException(
                        "VALIDATION_FAILED",
                        "The setting " + key.getKey() + " cannot be changed here.");
            }
            try {
                key.parse(entry.getValue());
            } catch (IllegalArgumentException exception) {
                throw new ValidationException("VALIDATION_FAILED", exception.getMessage());
            }
            validated.put(key.getKey(), entry.getValue() == null ? "" : entry.getValue().trim());
        }

        Instant now = Instant.now(clock);
        for (Map.Entry<String, String> entry : validated.entrySet()) {
            SettingKey key = SettingKey.fromKey(entry.getKey());
            Setting row =
                    settingRepository
                            .findBySettingKey(entry.getKey())
                            .orElseGet(
                                    () ->
                                            new Setting(
                                                    UUID.randomUUID(),
                                                    entry.getKey(),
                                                    entry.getValue(),
                                                    key.getType().name(),
                                                    now,
                                                    now));
            row.setSettingValue(entry.getValue());
            row.setValueType(key.getType().name());
            row.setUpdatedAt(now);
            settingRepository.save(row);
        }
        return validated;
    }
}
