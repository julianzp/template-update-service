package com.template_update_service.domain;

import java.util.Locale;

/**
 * No event in the consumed contract changes this: creation, apply and decline are
 * the only three. Closure and archival happen in the engagement management system,
 * which does not emit them, so the value arrives with seeding and nothing mutates
 * it in this slice.
 */
public enum EngagementStatus {
    ACTIVE,
    CLOSED,
    ARCHIVED;

    public boolean surfacesPendingUpdates() {
        return this == ACTIVE;
    }

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EngagementStatus fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
