package com.glofox.memberships.web;

import com.glofox.memberships.web.dto.FieldErrorResponse;
import com.glofox.memberships.web.dto.PurchaseRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shape validation: presence and size, reported all at once.
 *
 * <p>Collecting every error before returning means a client fixing a form gets
 * one round trip instead of three.
 *
 * <p><strong>Absence, length and the {@code start_date} format are checked
 * here.</strong> A malformed {@code plan_id} never reaches this method because
 * Jackson rejects it during binding; {@code start_date} arrives as a string and
 * is parsed strictly below. Blank-after-trim is the service's concern, since that is
 * a rule about member identity rather than about request shape — which is why
 * {@code ""} passes this validator and fails in the service.
 */
final class PurchaseRequestValidator {

    /**
     * Upper bound on the submitted name.
     *
     * <p>Not cosmetic. With no auth and no rate limit, an unbounded name is
     * retained several times over — normalised inside the member key, as a live
     * key in the claim store, and as the display string on every purchase — and
     * nothing evicts, because storage is in-memory. A few dozen multi-megabyte
     * requests would exhaust the heap.
     *
     * <p>It counts the name exactly as submitted, padding included, because that
     * is the string stored on the purchase.
     *
     * <p>The bound lives here rather than in {@code MemberKey} so the client
     * gets a 400 naming the field, instead of a mapped
     * {@code IllegalArgumentException}.
     */
    static final int MAX_MEMBER_NAME = 100;

    /**
     * Exactly {@code YYYY-MM-DD}. {@code ISO_LOCAL_DATE} alone would still
     * accept an expanded, signed year such as {@code +57443-04-15}, so the shape
     * is pinned first and the formatter then rejects impossible dates such as
     * {@code 2026-02-30} (it resolves strictly).
     */
    private static final Pattern CALENDAR_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private PurchaseRequestValidator() {
    }

    /** The parsed date, or empty if {@code raw} is not a calendar date. */
    static Optional<LocalDate> parseStartDate(String raw) {
        if (raw == null || !CALENDAR_DATE.matcher(raw).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (DateTimeParseException impossibleDate) {
            return Optional.empty();
        }
    }

    static List<FieldErrorResponse> validate(PurchaseRequest request) {
        List<FieldErrorResponse> errors = new ArrayList<>();

        if (request.memberName() == null) {
            errors.add(new FieldErrorResponse("member_name", "must be provided"));
        } else if (request.memberName().length() > MAX_MEMBER_NAME) {
            // Measured on the raw string because the raw string is what is
            // stored. Measuring after strip() let 10,000 spaces of padding
            // through, defeating the bound's purpose.
            errors.add(new FieldErrorResponse("member_name",
                    "must be at most " + MAX_MEMBER_NAME + " characters"));
        }
        if (request.planId() == null) {
            errors.add(new FieldErrorResponse("plan_id", "must be provided"));
        }
        if (request.startDate() == null) {
            errors.add(new FieldErrorResponse("start_date", "must be provided"));
        } else if (parseStartDate(request.startDate()).isEmpty()) {
            errors.add(new FieldErrorResponse("start_date",
                    "must be a calendar date in YYYY-MM-DD format"));
        }
        return errors;
    }
}
