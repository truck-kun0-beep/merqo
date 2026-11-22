package com.merqo.location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.access.LocationType;
import com.merqo.common.error.ValidationException;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the locations.
 *
 * <p>Responsibility: translate HTTP into calls on {@link LocationService}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/locations")
@Tag(name = "Locations", description = "Branches and warehouses")
public class LocationController {

    private final LocationService locationService;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param locationService the location rules
     * @param currentUserService the signed in user
     */
    public LocationController(
            LocationService locationService, CurrentUserService currentUserService) {
        this.locationService = locationService;
        this.currentUserService = currentUserService;
    }

    /**
     * The locations the caller may see.
     *
     * @return the locations
     */
    @GetMapping
    @Operation(summary = "The locations the caller may see")
    public List<Map<String, Object>> list() {
        AccessContext context = currentUserService.requireAccessContext();
        List<Map<String, Object>> items = new ArrayList<>();
        locationService
                .list(context.getScopeLocationIds(), context.isOwner())
                .forEach(location -> items.add(toSummary(location)));
        return items;
    }

    /**
     * One location.
     *
     * @param id the location
     * @return the location
     */
    @GetMapping("/{id}")
    @Operation(summary = "One location")
    public Map<String, Object> get(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        Location location =
                locationService.get(id, context.getScopeLocationIds(), context.isOwner());
        Map<String, Object> body = toSummary(location);
        List<Map<String, Object>> assignments = new ArrayList<>();
        locationService
                .assignments(id)
                .forEach(
                        assignment -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id", assignment.getId());
                            entry.put("userId", assignment.getUserId());
                            entry.put("assignmentType", assignment.getAssignmentType().name());
                            entry.put("startedAt", assignment.getStartedAt());
                            entry.put("endedAt", assignment.getEndedAt());
                            assignments.add(entry);
                        });
        body.put("assignments", assignments);
        return body;
    }

    /**
     * Creates a location. Only the owner may do this.
     *
     * @param request the location to create
     * @return the new location
     */
    @PostMapping
    @Operation(summary = "Create a location")
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateLocationRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        if (!context.isOwner()) {
            throw new com.merqo.common.error.ForbiddenException(
                    "LOCATION_MANAGE", "Only the owner may create a location.");
        }
        Location location =
                locationService.create(
                        request.code(),
                        request.name(),
                        request.locationType(),
                        request.address(),
                        request.phone(),
                        context.getUserId(),
                        context.getUsername());
        return ResponseEntity.status(201).body(toSummary(location));
    }

    /**
     * Edits a location. Only the owner may do this.
     *
     * @param id the location
     * @param request the new values
     * @return the changed location
     */
    @PutMapping("/{id}")
    @Operation(summary = "Edit a location")
    public Map<String, Object> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateLocationRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        if (!context.isOwner()) {
            throw new com.merqo.common.error.ForbiddenException(
                    "LOCATION_MANAGE", "Only the owner may edit a location.");
        }
        Location location =
                locationService.get(id, context.getScopeLocationIds(), context.isOwner());
        Location updated =
                locationService.update(
                        location,
                        request.name(),
                        request.address(),
                        request.phone(),
                        context.getUserId(),
                        context.getUsername());
        return toSummary(updated);
    }

    /**
     * Deactivates a location. Only the owner may do this.
     *
     * @param id the location
     * @return the deactivated location
     */
    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a location")
    public Map<String, Object> deactivate(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        if (!context.isOwner()) {
            throw new com.merqo.common.error.ForbiddenException(
                    "LOCATION_MANAGE", "Only the owner may deactivate a location.");
        }
        Location location =
                locationService.get(id, context.getScopeLocationIds(), context.isOwner());
        return toSummary(
                locationService.deactivate(location, context.getUserId(), context.getUsername()));
    }

    /**
     * Activates a location again. Only the owner may do this.
     *
     * @param id the location
     * @return the activated location
     */
    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a location again")
    public Map<String, Object> activate(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        if (!context.isOwner()) {
            throw new com.merqo.common.error.ForbiddenException(
                    "LOCATION_MANAGE", "Only the owner may activate a location.");
        }
        Location location =
                locationService.get(id, context.getScopeLocationIds(), context.isOwner());
        return toSummary(
                locationService.activate(location, context.getUserId(), context.getUsername()));
    }

    /**
     * Makes one warehouse the online fulfilment location. Only the owner may do this.
     *
     * @param id the location
     * @return the new online fulfilment location
     */
    @PostMapping("/{id}/online-fulfillment")
    @Operation(summary = "Make this location fulfil the online orders")
    public Map<String, Object> setOnlineFulfillment(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        if (!context.isOwner()) {
            throw new com.merqo.common.error.ForbiddenException(
                    "LOCATION_MANAGE", "Only the owner may change the online fulfilment location.");
        }
        Location location =
                locationService.get(id, context.getScopeLocationIds(), context.isOwner());
        if (!location.isActive()) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "Only an active location may fulfil online orders.");
        }
        Location updated =
                locationService.setOnlineFulfillment(
                        location, context.getUserId(), context.getUsername());
        return toSummary(updated);
    }

    private Map<String, Object> toSummary(Location location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", location.getId());
        body.put("code", location.getCode());
        body.put("name", location.getName());
        body.put("locationType", location.getLocationType().name());
        body.put("address", location.getAddress());
        body.put("phone", location.getPhone());
        body.put("isActive", location.isActive());
        body.put("isOnlineFulfillment", location.isOnlineFulfillment());
        return body;
    }

    /**
     * The body of a create location request.
     *
     * @param code the short code
     * @param name the display name
     * @param locationType the kind of location
     * @param address the address
     * @param phone the phone number
     */
    public record CreateLocationRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull LocationType locationType,
            String address,
            String phone) {}

    /**
     * The body of an update location request.
     *
     * @param name the display name
     * @param address the address
     * @param phone the phone number
     */
    public record UpdateLocationRequest(String name, String address, String phone) {}
}
