package com.merqo.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.access.AccountType;

/**
 * One account of the Merqo system.
 *
 * <p>Responsibility: hold the data of one account. The rules about what an account may do live in
 * the access module, not here.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the password hash is only ever read by the password service; the
 *       field has no public setter.
 *   <li><b>Mapping</b> - the class describes how the object is stored, and nothing else.
 * </ul>
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id private UUID id;

    @Column(name = "username", nullable = false, length = 40)
    private String username;

    @Column(name = "email", length = 190)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "approval_pin_hash", length = 255)
    private String approvalPinHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private AccountType accountType;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "employee_code", length = 20)
    private String employeeCode;

    @Column(name = "max_discount_percent", nullable = false)
    private java.math.BigDecimal maxDiscountPercent = java.math.BigDecimal.ZERO;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected AppUser() {}

    /**
     * Creates an account.
     *
     * @param id the id
     * @param username the login name
     * @param email the optional email
     * @param fullName the display name
     * @param passwordHash the BCrypt hash of the password
     * @param accountType the kind of account
     * @param createdAt when the account was created
     */
    public AppUser(
            UUID id,
            String username,
            String email,
            String fullName,
            String passwordHash,
            AccountType accountType,
            Instant createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.accountType = accountType;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.active = true;
        this.mustChangePassword = false;
        this.demo = false;
        this.maxDiscountPercent = java.math.BigDecimal.ZERO;
    }

    /**
     * The id of the account.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The login name.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * The email, or {@code null}.
     *
     * @return the email
     */
    public String getEmail() {
        return email;
    }

    /**
     * Sets the email.
     *
     * @param email the email
     */
    public void setEmail(String email) {
        this.email = email;
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
     * The display name.
     *
     * @return the full name
     */
    public String getFullName() {
        return fullName;
    }

    /**
     * Sets the display name.
     *
     * @param fullName the full name
     */
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /**
     * The BCrypt hash of the password.
     *
     * <p>Never leave the backend. The API never returns this value.
     *
     * @return the hash
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Sets the password hash.
     *
     * @param passwordHash the new hash
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * The BCrypt hash of the manager approval PIN, or {@code null} if not set.
     *
     * <p>Never leaves the backend.
     *
     * @return the PIN hash
     */
    public String getApprovalPinHash() {
        return approvalPinHash;
    }

    /**
     * Sets the manager approval PIN hash.
     *
     * @param approvalPinHash the BCrypt PIN hash
     */
    public void setApprovalPinHash(String approvalPinHash) {
        this.approvalPinHash = approvalPinHash;
    }

    /**
     * The kind of account.
     *
     * @return the account type
     */
    public AccountType getAccountType() {
        return accountType;
    }

    /**
     * Whether the account may sign in.
     *
     * @return {@code true} when the account is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Activates or deactivates the account.
     *
     * @param active the new state
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Whether the user has to change the password before doing anything else.
     *
     * @return {@code true} when a change is required
     */
    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    /**
     * Sets the forced password change flag.
     *
     * @param mustChangePassword the new state
     */
    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    /**
     * How many failed logins happened in a row.
     *
     * @return the counter
     */
    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    /**
     * Sets the failed login counter.
     *
     * @param failedLoginCount the new counter
     */
    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    /**
     * When the lock ends, or {@code null} when the account is not locked.
     *
     * @return the moment the lock ends
     */
    public Instant getLockedUntil() {
        return lockedUntil;
    }

    /**
     * Sets the lock.
     *
     * @param lockedUntil when the lock ends
     */
    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    /**
     * When the user last signed in.
     *
     * @return the moment of the last login
     */
    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /**
     * Records a successful login.
     *
     * @param lastLoginAt when it happened
     */
    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    /**
     * The human readable staff number, for example {@code EMP-0001}.
     *
     * @return the employee code
     */
    public String getEmployeeCode() {
        return employeeCode;
    }

    /**
     * Sets the employee code.
     *
     * @param employeeCode the employee code
     */
    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode;
    }

    /**
     * The largest discount this person may apply without asking.
     *
     * @return the percentage
     */
    public java.math.BigDecimal getMaxDiscountPercent() {
        return maxDiscountPercent;
    }

    /**
     * Sets the discount ceiling.
     *
     * @param maxDiscountPercent the percentage
     */
    public void setMaxDiscountPercent(java.math.BigDecimal maxDiscountPercent) {
        this.maxDiscountPercent = maxDiscountPercent;
    }

    /**
     * Whether this is a demo account.
     *
     * @return {@code true} for a demo account
     */
    public boolean isDemo() {
        return demo;
    }

    /**
     * Marks the account as a demo account.
     *
     * @param demo the new state
     */
    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    /**
     * The stable key of the demo account.
     *
     * @return the demo key
     */
    public String getDemoKey() {
        return demoKey;
    }

    /**
     * Sets the demo key.
     *
     * @param demoKey the demo key
     */
    public void setDemoKey(String demoKey) {
        this.demoKey = demoKey;
    }

    /**
     * The id of the account that created this one.
     *
     * @return the creator id
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * Sets the creator.
     *
     * @param createdBy the creator id
     */
    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * When the account was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the account was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the account was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Whether this account is the owner.
     *
     * @return {@code true} for the owner
     */
    public boolean isOwner() {
        return accountType == AccountType.OWNER;
    }
}
