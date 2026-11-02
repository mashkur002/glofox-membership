package com.glofox.memberships.store;

import com.glofox.memberships.domain.Purchase;

/**
 * Deduplicates retried purchase requests by caller-supplied key.
 *
 * <p>This is the same claim-or-fail shape as {@link InventoryStore} and
 * {@link UnlimitedClaimStore}: claim atomically, do the work, release if
 * anything fails. The third instance of one pattern rather than a third
 * pattern.
 *
 * <h2>Replay semantics: the original response, not the current resource</h2>
 *
 * A completed key stores the {@link Purchase} exactly as it was returned to the
 * first caller. {@code Purchase} is an immutable record, so that snapshot is the
 * original response: a retry after a cancel still replays {@code active}. Clients
 * that want the latest state use resource retrieval
 * ({@code GET /v1/memberships/purchases/{id}}) instead.
 */
public interface IdempotencyStore {

    /**
     * What a claim attempt found.
     *
     * <p>Sealed, and not a boolean, because there are four distinct answers and
     * conflating any two of them is a bug. Exhaustive {@code switch} means a
     * fifth state could not be added and silently ignored.
     */
    sealed interface Claim {

        /** The key is ours; proceed with the purchase. */
        record Acquired() implements Claim {
        }

        /** Another request holds this key and has not finished. */
        record InProgress() implements Claim {
        }

        /** This key already produced a purchase; return it as originally returned. */
        record Replay(Purchase original) implements Claim {
        }

        /** This key was used for a different payload. */
        record Conflict() implements Claim {
        }
    }

    /**
     * Attempts to claim {@code key} for a request with this {@code fingerprint}.
     *
     * @param fingerprint a digest of the request payload, so reuse of one key
     *                    with different content is detectable
     */
    Claim claim(String key, String fingerprint);

    /**
     * Records the purchase this key produced, as returned to the caller, so
     * later retries replay that exact response.
     */
    void complete(String key, Purchase original);

    /**
     * Gives the key back after a failure.
     *
     * <p>Without this, a client retrying after a declined card would be told its
     * request is already in progress forever.
     */
    void release(String key);
}
