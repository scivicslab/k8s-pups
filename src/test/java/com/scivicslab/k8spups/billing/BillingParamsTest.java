package com.scivicslab.k8spups.billing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BillingParamsTest {

    @Test
    void anInstantIsReadInIso8601() {
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), BillingParams.instant("2026-11-01T00:00:00Z", "from"));
    }

    @Test
    void anAbsentInstantIsNull() {
        assertNull(BillingParams.instant(null, "from"));
        assertNull(BillingParams.instant("  ", "from"));
    }

    @Test
    void anInstantThatIsNotIso8601IsRefusedWithTheNameOfTheParameter() {
        var e = assertThrows(IllegalArgumentException.class, () -> BillingParams.instant("yesterday", "from"));
        assertEquals("from must be an ISO 8601 instant such as 2026-11-01T00:00:00Z: yesterday", e.getMessage());
    }

    @Test
    void theLimitDefaultsTo500() {
        assertEquals(500, BillingParams.limit(null));
        assertEquals(500, BillingParams.limit(""));
    }

    @Test
    void theLimitIsReadWithinTheRange() {
        assertEquals(1, BillingParams.limit("1"));
        assertEquals(1000, BillingParams.limit("1000"));
    }

    @Test
    void aLimitOutsideTheRangeOrNotANumberIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BillingParams.limit("0"));
        assertThrows(IllegalArgumentException.class, () -> BillingParams.limit("1001"));
        assertThrows(IllegalArgumentException.class, () -> BillingParams.limit("many"));
    }

    @Test
    void aDayIsReadInIso8601AndIsRequired() {
        assertEquals(LocalDate.of(2026, 11, 3), BillingParams.day("2026-11-03", "from"));
        var e = assertThrows(IllegalArgumentException.class, () -> BillingParams.day(null, "from"));
        assertEquals("from is required, as a date such as 2026-11-01", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BillingParams.day("2026/11/03", "from"));
    }

    @Test
    void theRangeOfDaysMustNotBeReversedOrLongerThanOneYear() {
        BillingParams.checkDays(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        BillingParams.checkDays(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BillingParams.checkDays(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)));
        assertThrows(IllegalArgumentException.class,
            () -> BillingParams.checkDays(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 3)));
    }
}
