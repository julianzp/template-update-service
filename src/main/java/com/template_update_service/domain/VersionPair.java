package com.template_update_service.domain;

/**
 * Always {@code applied -> latest}, never a step in a chain: a field changed in v2
 * and reverted in v3 appears twice in a chain of per-version diffs, and correctly
 * not at all in a single v1 -> v4 pair. A decline suppresses exactly one pair, and
 * a summary is computed once per pair and shared across every firm on it.
 */
public record VersionPair(TemplateVersion from, TemplateVersion to) {

    public VersionPair {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("pair must move forward, was " + from + " -> " + to);
        }
    }

    public static VersionPair of(int from, int to) {
        return new VersionPair(TemplateVersion.of(from), TemplateVersion.of(to));
    }

    @Override
    public String toString() {
        return from + "->" + to;
    }
}
