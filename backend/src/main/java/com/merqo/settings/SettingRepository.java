package com.merqo.settings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The database access for the settings.
 *
 * <p>Responsibility: read and write the {@code settings} table.
 *
 * <p>OOP concept: the interface is the contract the service depends on.
 */
public interface SettingRepository extends JpaRepository<Setting, UUID> {

    /**
     * Finds a setting by its key.
     *
     * @param settingKey the key
     * @return the setting, if it was written
     */
    Optional<Setting> findBySettingKey(String settingKey);

    /**
     * Finds every setting that was written.
     *
     * @return the settings
     */
    List<Setting> findAllByOrderBySettingKeyAsc();
}
