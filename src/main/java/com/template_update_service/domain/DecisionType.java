package com.template_update_service.domain;

import java.util.Locale;

public enum DecisionType {
    APPLIED,
    DECLINED;

    /**
     * An applied decision needs no suppression: the projection's applied_version has
     * already moved past the pair.
     */
    public boolean suppressesPendingUpdate() {
        return this == DECLINED;
    }

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
