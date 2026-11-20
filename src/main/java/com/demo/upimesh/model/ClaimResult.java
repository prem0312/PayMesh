package com.demo.upimesh.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of an idempotency claim attempt.
 */
public record ClaimResult(
        Status status,
        UUID claimToken,
        String message
) {
    public enum Status {
        ACQUIRED,             // Newly acquired claim
        RECOVERED,            // Successfully recovered a stale claim
        RETRIED,              // Successfully retried a previously FAILED claim
        ALREADY_OWNED,        // Active claim held by another worker
        TERMINAL_DUPLICATE    // Terminal state (SETTLED, REJECTED, INVALID)
    }

    public boolean isAcquired() {
        return status == Status.ACQUIRED || status == Status.RECOVERED || status == Status.RETRIED;
    }

    public static ClaimResult acquired(UUID claimToken) {
        return new ClaimResult(Status.ACQUIRED, Objects.requireNonNull(claimToken), "Claim acquired");
    }

    public static ClaimResult recovered(UUID claimToken) {
        return new ClaimResult(Status.RECOVERED, Objects.requireNonNull(claimToken), "Stale claim recovered");
    }

    public static ClaimResult retried(UUID claimToken) {
        return new ClaimResult(Status.RETRIED, Objects.requireNonNull(claimToken), "Failed claim retried");
    }

    public static ClaimResult alreadyOwned() {
        return new ClaimResult(Status.ALREADY_OWNED, null, "Active claim held by another worker");
    }

    public static ClaimResult terminalDuplicate(String state) {
        return new ClaimResult(Status.TERMINAL_DUPLICATE, null, "Terminal record already exists: " + state);
    }
}
