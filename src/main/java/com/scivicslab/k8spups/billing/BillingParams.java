package com.scivicslab.k8spups.billing;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/** Reads and checks the query parameters of the billing API. Every refusal is an {@link IllegalArgumentException} whose message tells the caller what is wrong. */
public final class BillingParams {

    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 1000;
    /** The longest range of days one request may ask for: one year. */
    public static final long MAX_DAYS = 366;

    private BillingParams() {}

    /** An optional instant in ISO 8601; null when the parameter is absent. */
    public static Instant instant(String text, String name) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO 8601 instant such as 2026-11-01T00:00:00Z: " + text);
        }
    }

    /** The number of rows one response holds; {@value #DEFAULT_LIMIT} when absent. */
    public static int limit(String text) {
        if (text == null || text.isBlank()) return DEFAULT_LIMIT;
        int n;
        try {
            n = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be a whole number from 1 to " + MAX_LIMIT + ": " + text);
        }
        if (n < 1 || n > MAX_LIMIT) throw new IllegalArgumentException("limit must be a whole number from 1 to " + MAX_LIMIT + ": " + text);
        return n;
    }

    /** A required date in ISO 8601, as a day of Japan time. */
    public static LocalDate day(String text, String name) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(name + " is required, as a date such as 2026-11-01");
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(name + " must be a date such as 2026-11-01: " + text);
        }
    }

    /** The range is not reversed and holds at most {@value #MAX_DAYS} days counting both ends. */
    public static void checkDays(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("to must not be before from: " + from + " " + to);
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS)
            throw new IllegalArgumentException("a request may ask for at most " + MAX_DAYS + " days: " + from + " to " + to);
    }
}
