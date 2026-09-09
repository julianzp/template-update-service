package com.template_update_service.domain;

/**
 * The integer is a sequence number, not a label: "2024.1" is what the user sees,
 * {@code 7} is what orders it. Ordering is per template, so nothing here takes a
 * template id -- the caller already knows which template it holds.
 */
public record TemplateVersion(int seq) implements Comparable<TemplateVersion> {

    public TemplateVersion {
        if (seq < 1) {
            throw new IllegalArgumentException("version seq must be positive, was " + seq);
        }
    }

    public static TemplateVersion of(int seq) {
        return new TemplateVersion(seq);
    }

    public boolean isAfter(TemplateVersion other) {
        return seq > other.seq;
    }

    @Override
    public int compareTo(TemplateVersion other) {
        return Integer.compare(seq, other.seq);
    }

    @Override
    public String toString() {
        return "v" + seq;
    }
}
