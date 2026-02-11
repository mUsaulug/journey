package com.banking.journey.domain.valueobject;

/**
 * Customer segment classification.
 * <p>
 * Determines eligibility for premium offers, personalized messaging,
 * and priority processing in the journey orchestration engine.
 * </p>
 *
 * <p><b>Domain Rule:</b> VIP customers receive enhanced notifications
 * and faster processing guarantees.</p>
 */
public enum Segment {

    NEW_CUSTOMER("New Customer"),
    REGULAR("Regular"),
    VIP("VIP");

    private final String displayName;

    Segment(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Checks if this segment qualifies for premium treatment.
     *
     * @return true if VIP segment
     */
    public boolean isPremium() {
        return this == VIP;
    }

    /**
     * Checks if this is a newly acquired customer.
     *
     * @return true if NEW_CUSTOMER segment
     */
    public boolean isNew() {
        return this == NEW_CUSTOMER;
    }

    public String getDisplayName() {
        return displayName;
    }
}
