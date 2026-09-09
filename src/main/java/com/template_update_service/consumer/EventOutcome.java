package com.template_update_service.consumer;

public enum EventOutcome {

    APPLIED,

    /** Already in the inbox. No writes happened. */
    DUPLICATE,

    /**
     * Sequence at or below the stored high-water mark. Terminal: it can never become
     * fresh again, so it is recorded and acknowledged rather than left to redeliver.
     */
    STALE,

    /**
     * A decision event whose EngagementCreated has not arrived, so firm_id and
     * template_id cannot be resolved. Deliberately absent from the inbox -- that
     * absence is what makes redelivery correct.
     */
    PARKED;

    public boolean shouldRedeliver() {
        return this == PARKED;
    }
}
