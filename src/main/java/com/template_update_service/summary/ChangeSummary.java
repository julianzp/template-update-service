package com.template_update_service.summary;

import com.template_update_service.domain.VersionPair;
import java.util.Locale;
import java.util.UUID;

/** One narrative per version pair, shared by every firm sitting on that pair. */
public record ChangeSummary(UUID id, UUID templateId, VersionPair versions,
                            Status status, String headline) {

    /**
     * Generation lifecycle only. Whether a summary is still <em>current</em> is a
     * separate axis, carried by {@code change_summary.superseded_by} -- a summary can
     * be both superseded and failed, and one column cannot say so.
     */
    public enum Status {
        /**
         * No row at all: what the outer join yields, and what the UI renders when a
         * pending update is real but its prose is not written yet. Not a database
         * value -- a first-class case here so no call site handles it by accident.
         */
        ABSENT,
        PENDING,
        READY,
        FAILED;

        public static Status fromDb(String value) {
            return value == null ? ABSENT : valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
