package com.merqo.access;

import java.util.Set;
import java.util.UUID;

import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;

/**
 * What the signed in user may do, for the length of one request.
 *
 * <p>Responsibility: hold the effective permissions of the caller and answer the three questions a
 * service asks: "may I do this everywhere?", "may I do this at this location?" and "which locations
 * may I see?".
 *
 * <p>The error rules of the access model live here:
 *
 * <ul>
 *   <li>The user lacks the permission at a location they can otherwise see: {@code 403 FORBIDDEN}.
 *   <li>The target location or record is outside their scope: {@code 404 NOT_FOUND}, so the caller
 *       cannot even learn that it exists.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the services never look at the permission sets themselves; they ask
 *       this object.
 *   <li><b>Single responsibility</b> - one object, one job: describe what the caller may do.
 *   <li><b>Composition</b> - the object is built from the user, the resolved permissions and the
 *       scope instead of growing new fields later.
 *   <li><b>Fail fast</b> - {@link #require} and {@link #requireGlobal} throw, so a service cannot
 *       forget to check.
 * </ul>
 */
public class AccessContext {

    private final UUID userId;
    private final String username;
    private final AccountType accountType;
    private final EffectivePermissions permissions;
    private final Set<UUID> scopeLocationIds;
    private final boolean mustChangePassword;

    /**
     * Creates an access context.
     *
     * @param userId the id of the caller
     * @param username the username of the caller
     * @param accountType the kind of account
     * @param permissions the resolved permissions
     * @param scopeLocationIds the locations the caller may see
     * @param mustChangePassword whether the caller must change the password first
     */
    public AccessContext(
            UUID userId,
            String username,
            AccountType accountType,
            EffectivePermissions permissions,
            Set<UUID> scopeLocationIds,
            boolean mustChangePassword) {
        this.userId = userId;
        this.username = username;
        this.accountType = accountType;
        this.permissions = permissions;
        this.scopeLocationIds = Set.copyOf(scopeLocationIds);
        this.mustChangePassword = mustChangePassword;
    }

    /**
     * The id of the caller.
     *
     * @return the user id
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * The username of the caller.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * The kind of account of the caller.
     *
     * @return the account type
     */
    public AccountType getAccountType() {
        return accountType;
    }

    /**
     * The resolved permissions of the caller.
     *
     * @return the permissions
     */
    public EffectivePermissions getPermissions() {
        return permissions;
    }

    /**
     * Whether the caller must change the password before doing anything else.
     *
     * @return {@code true} when a change is required
     */
    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    /**
     * Whether the caller is the owner.
     *
     * @return {@code true} for the owner
     */
    public boolean isOwner() {
        return accountType == AccountType.OWNER;
    }

    /**
     * Whether the caller may do something that is not tied to one location.
     *
     * @param permission the permission
     * @return {@code true} when the permission is held
     */
    public boolean can(Permission permission) {
        return permissions.can(permission);
    }

    /**
     * Whether the caller may do something at one location.
     *
     * @param permission the permission
     * @param locationId the location
     * @return {@code true} when the permission is held there
     */
    public boolean can(Permission permission, UUID locationId) {
        return permissions.can(permission, locationId);
    }

    /**
     * Refuses the request when the caller does not hold a global permission.
     *
     * @param permission the permission
     * @throws ForbiddenException when the permission is missing
     */
    public void requireGlobal(Permission permission) {
        if (!permissions.can(permission)) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "You do not have the permission " + permission.name() + ".");
        }
    }

    /**
     * Refuses the request when the caller does not hold a permission at a location.
     *
     * @param permission the permission
     * @param locationId the location
     * @throws ForbiddenException when the permission is missing
     */
    public void require(Permission permission, UUID locationId) {
        if (!permissions.can(permission, locationId)) {
            throw new ForbiddenException(
                    ErrorCodes.FORBIDDEN,
                    "You do not have the permission " + permission.name() + " at this location.");
        }
    }

    /**
     * The locations at which the caller may do something.
     *
     * <p>Every list query uses this to scope its search. The answer is empty when the caller may
     * not do it anywhere, so the query returns nothing instead of everything.
     *
     * @param permission the permission
     * @return the location ids
     */
    public Set<UUID> visibleLocationIds(Permission permission) {
        return permissions.locationsFor(permission);
    }

    /**
     * The locations at which the caller may do something, where the owner may do it everywhere.
     *
     * <p>The owner holds every permission everywhere, so the owner may see every location of the
     * business. The list of the locations that exist is passed in, because the access module does
     * not know which locations there are: that is the business of the location module.
     *
     * @param permission the permission
     * @param allLocationIds the ids of every location of the business
     * @return the location ids, never {@code null}
     */
    public Set<UUID> visibleLocationIds(Permission permission, Set<UUID> allLocationIds) {
        if (isOwner()) {
            return Set.copyOf(allLocationIds);
        }
        return visibleLocationIds(permission);
    }

    /**
     * The locations the caller is assigned to.
     *
     * @return the location ids
     */
    public Set<UUID> getScopeLocationIds() {
        return scopeLocationIds;
    }

    /**
     * Whether the caller may see a location at all.
     *
     * @param locationId the location
     * @return {@code true} when the location is inside the caller's scope
     */
    public boolean isInScope(UUID locationId) {
        return isOwner() || scopeLocationIds.contains(locationId);
    }

    /**
     * Refuses the request when the location is outside the caller's scope.
     *
     * <p>The answer is a {@code 404}, not a {@code 403}, so the caller cannot learn that the
     * location exists.
     *
     * @param locationId the location
     * @throws NotFoundException when the location is outside the scope
     */
    public void requireInScope(UUID locationId) {
        if (!isInScope(locationId)) {
            throw new NotFoundException("The requested record was not found.");
        }
    }

    /**
     * Whether the caller may look after the account of somebody else.
     *
     * <p>The owner may look after everybody. A manager may look after the staff inside their own
     * scope. Nobody may look after another manager or the owner.
     *
     * @param targetAccountType the kind of account of the target
     * @param targetIsInScope whether the target has an assignment inside the caller's scope
     * @return {@code true} when the caller may act on the target
     */
    public boolean mayManageAccount(AccountType targetAccountType, boolean targetIsInScope) {
        if (isOwner()) {
            return true;
        }
        if (targetAccountType == AccountType.OWNER || targetAccountType == AccountType.MANAGER) {
            return false;
        }
        return targetIsInScope;
    }
}
