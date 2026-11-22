package com.merqo.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.merqo.access.AccountType;

/**
 * The database access for accounts.
 *
 * <p>Responsibility: read and write the {@code app_users} table. The business rules live in the
 * services; this interface only knows how to find rows.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface</b> - Spring Data creates the implementation, so the service depends on the
 *       idea "a store of accounts" and not on the framework.
 *   <li><b>Dependency inversion</b> - the service asks for this interface, so a test can replace
 *       it.
 * </ul>
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Finds an account by its login name, ignoring the case.
     *
     * @param username the login name
     * @return the account, if it exists
     */
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    /**
     * Finds an account by its email address, ignoring the case.
     *
     * @param email the email address
     * @return the account, if it exists
     */
    Optional<AppUser> findByEmailIgnoreCase(String email);

    /**
     * Finds an account by its login name or its email address, ignoring the case.
     *
     * @param identifier the login name or the email address
     * @return the account, if it exists
     */
    @Query(
            """
            select u from AppUser u
            where lower(u.username) = lower(:identifier)
               or (u.email is not null and lower(u.email) = lower(:identifier))
            """)
    Optional<AppUser> findByIdentifier(@Param("identifier") String identifier);

    /**
     * Whether an account with this login name exists.
     *
     * @param username the login name
     * @return {@code true} when it exists
     */
    boolean existsByUsernameIgnoreCase(String username);

    /**
     * Whether an account with this email address exists.
     *
     * @param email the email address
     * @return {@code true} when it exists
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Finds the owner account.
     *
     * @return the owner, if it exists
     */
    Optional<AppUser> findFirstByAccountType(AccountType accountType);

    /**
     * Counts the accounts of one type.
     *
     * @param accountType the type
     * @return the number of accounts
     */
    long countByAccountType(AccountType accountType);

    /**
     * Finds a demo account by its stable demo key.
     *
     * @param demoKey the demo key
     * @return the account, if it exists
     */
    Optional<AppUser> findByDemoKey(String demoKey);

    /**
     * Finds every demo account, ordered by the demo key.
     *
     * @return the demo accounts
     */
    List<AppUser> findByDemoTrueOrderByDemoKeyAsc();

    /**
     * Searches the accounts the caller may see.
     *
     * <p>The scope is applied in the query, never in memory. The owner passes an empty location set
     * and sees everything; everybody else passes the locations they are assigned to.
     *
     * <p><b>Why the filters are written with {@code coalesce} and not with {@code :filter is
     * null}.</b> A parameter that only appears in a null check has no type of its own, so Hibernate
     * sends it as an untyped null and PostgreSQL cannot tell what it is. The database then refuses
     * the whole query. Putting the parameter next to the column it filters gives it that type, and
     * the meaning stays the same: an absent filter does not narrow the result.
     *
     * @param search the free text search, may be {@code null}
     * @param accountType the account type filter, may be {@code null}
     * @param active the active filter, may be {@code null}
     * @param locationIds the locations the caller may see
     * @param allLocations whether the caller may see every location
     * @param pageable the paging
     * @return the page of accounts
     */
    @Query(
            """
            select distinct u from AppUser u
            where (:allLocations = true
                   or u.id in (select a.userId from LocationAssignment a
                                where a.endedAt is null
                                  and a.locationId in :locationIds))
              and (coalesce(:search, '') = ''
                   or lower(u.username) like lower(concat('%', :search, '%'))
                   or lower(u.fullName) like lower(concat('%', :search, '%'))
                   or lower(coalesce(u.email, '')) like lower(concat('%', :search, '%'))
                   or lower(coalesce(u.employeeCode, '')) like lower(concat('%', :search, '%')))
              and coalesce(:accountType, u.accountType) = u.accountType
              and coalesce(:active, u.active) = u.active
            """)
    Page<AppUser> search(
            @Param("search") String search,
            @Param("accountType") AccountType accountType,
            @Param("active") Boolean active,
            @Param("locationIds") List<UUID> locationIds,
            @Param("allLocations") boolean allLocations,
            Pageable pageable);

    /**
     * Finds the accounts that hold one template.
     *
     * @param templateId the template
     * @return the accounts
     */
    @Query(
            "select u from AppUser u join UserTemplateAssignment ut on ut.userId = u.id "
                    + "where ut.templateId = :templateId")
    List<AppUser> findByTemplateId(@Param("templateId") UUID templateId);

    /**
     * Finds the accounts whose last login was before a moment.
     *
     * @param moment the moment
     * @return the accounts
     */
    List<AppUser> findByLastLoginAtBefore(Instant moment);
}
