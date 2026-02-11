package com.banking.journey.domain.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.banking.journey.domain.valueobject.Segment;

/**
 * Customer entity representing a bank customer in the journey orchestration
 * system.
 * <p>
 * Immutable, behavior-rich domain entity. Encapsulates customer classification
 * logic used for personalized journey experiences (VIP messaging, premium
 * offers).
 * </p>
 *
 * <p>
 * <b>Invariants:</b>
 * </p>
 * <ul>
 * <li>customerId is non-null, non-blank</li>
 * <li>segment is non-null</li>
 * <li>registrationDate is non-null, not in the future</li>
 * </ul>
 */
public final class Customer {

    private static final int NEW_CUSTOMER_THRESHOLD_DAYS = 30;
    private static final int TENURE_THRESHOLD_DAYS = 365;

    private final String customerId;
    private final Segment segment;
    private final Instant registrationDate;
    private final String email;

    /**
     * Creates a new Customer instance with full validation.
     *
     * @param customerId       unique customer identifier
     * @param segment          customer segment classification
     * @param registrationDate when the customer registered
     * @param email            customer email address
     * @throws IllegalArgumentException if any validation fails
     */
    public Customer(String customerId, Segment segment, Instant registrationDate, String email) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId cannot be null or blank");
        }
        if (segment == null) {
            throw new IllegalArgumentException("segment cannot be null");
        }
        if (registrationDate == null) {
            throw new IllegalArgumentException("registrationDate cannot be null");
        }
        if (email != null && !email.isBlank() && !email.matches(".*@.*\\..*")) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }

        this.customerId = customerId;
        this.segment = segment;
        this.registrationDate = registrationDate;
        this.email = email;
    }

    /**
     * Convenience constructor without email.
     */
    public Customer(String customerId, Segment segment) {
        this(customerId, segment, Instant.now(), null);
    }

    // ─────────────────── Behavior Methods ───────────────────

    /**
     * Checks if the customer is a VIP segment member.
     *
     * @return true if VIP
     */
    public boolean isVip() {
        return segment.isPremium();
    }

    /**
     * Checks if the customer registered within the last 30 days.
     *
     * @return true if registration is less than 30 days ago
     */
    public boolean isNewCustomer() {
        return daysSinceRegistration() < NEW_CUSTOMER_THRESHOLD_DAYS;
    }

    /**
     * Checks eligibility for premium offers.
     * <p>
     * A customer is eligible if they are VIP OR have been
     * a customer for more than 1 year.
     * </p>
     *
     * @return true if eligible for premium offers
     */
    public boolean isEligibleForPremiumOffer() {
        return isVip() || daysSinceRegistration() > TENURE_THRESHOLD_DAYS;
    }

    /**
     * Calculates number of days since customer registration.
     *
     * @return days since registration (non-negative)
     */
    public long daysSinceRegistration() {
        Duration duration = Duration.between(registrationDate, Instant.now());
        return Math.max(0, duration.toDays());
    }

    // ─────────────────── Getters ───────────────────

    public String getCustomerId() {
        return customerId;
    }

    public Segment getSegment() {
        return segment;
    }

    public Instant getRegistrationDate() {
        return registrationDate;
    }

    public String getEmail() {
        return email;
    }

    // ─────────────────── Identity ───────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Customer customer = (Customer) o;
        return Objects.equals(customerId, customer.customerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId);
    }

    @Override
    public String toString() {
        return "Customer{customerId='" + customerId + "', segment=" + segment + "}";
    }
}
