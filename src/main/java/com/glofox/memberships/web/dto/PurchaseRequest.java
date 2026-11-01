package com.glofox.memberships.web.dto;

import java.util.UUID;

/**
 * Incoming purchase request. Wire names are snake_case via the global naming
 * strategy, so {@code memberName} arrives as {@code member_name}.
 *
 * <p>{@code planId} is typed, so Jackson rejects a malformed UUID during
 * binding. {@code startDate} is deliberately a {@code String}: Jackson's
 * {@code LocalDate} reader is lenient — it truncates {@code "2026-12-01T10:00Z"}
 * to a date and reads the integer {@code 20261201} as an epoch day (year
 * 57443). The contract is a calendar date and nothing else, so the web layer
 * parses it strictly in {@code PurchaseRequestValidator}.
 */
public record PurchaseRequest(String memberName, UUID planId, String startDate) {
}
