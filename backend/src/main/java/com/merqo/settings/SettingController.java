package com.merqo.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ForbiddenException;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the business settings.
 *
 * <p>Responsibility: translate HTTP into calls on {@link SettingService}.
 *
 * <p>Reading needs {@code SETTINGS_VIEW}; writing is reserved for the owner.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Facade</b> - the settings screen never talks to the repository.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "Settings", description = "The typed business settings")
public class SettingController {

    private final SettingService settingService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    /**
     * Creates the controller.
     *
     * @param settingService the settings
     * @param currentUserService the signed in user
     * @param auditService the audit log
     */
    public SettingController(
            SettingService settingService,
            CurrentUserService currentUserService,
            AuditService auditService) {
        this.settingService = settingService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /**
     * Every setting, grouped for the settings screen.
     *
     * @return the settings
     */
    @GetMapping
    @Operation(summary = "Every business setting")
    public Map<String, Object> get() {
        AccessContext context = currentUserService.requireAccessContext();
        context.requireGlobal(Permission.SETTINGS_VIEW);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groups", settingService.allGrouped());
        return body;
    }

    /**
     * Writes a batch of settings. Every value is validated before anything is written.
     *
     * @param request the key and value pairs
     * @return the values that were written
     */
    @PutMapping
    @Operation(summary = "Write a batch of settings")
    public Map<String, String> update(@Valid @RequestBody UpdateSettingsRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        if (!context.isOwner()) {
            throw new ForbiddenException(
                    "SETTINGS_MANAGE", "Only the owner may change the business settings.");
        }
        Map<String, String> oldValues = new LinkedHashMap<>();
        request.values()
                .keySet()
                .forEach(
                        key -> {
                            SettingKey settingKey = SettingKey.fromKey(key);
                            if (settingKey != null) {
                                oldValues.put(key, settingService.getString(settingKey));
                            }
                        });

        Map<String, String> written = settingService.writeAll(request.values());

        auditService.record(
                new AuditEvent.Builder(AuditAction.SETTING_CHANGED, "settings")
                        .entityId(null)
                        .actor(context.getUserId(), context.getUsername())
                        .changed("values", oldValues, written)
                        .build());
        return written;
    }

    /**
     * The body of an update settings request.
     *
     * @param values the key and value pairs
     */
    public record UpdateSettingsRequest(@NotNull Map<String, String> values) {}
}
