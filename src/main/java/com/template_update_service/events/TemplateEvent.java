package com.template_update_service.events;

import com.template_update_service.domain.DecisionType;
import com.template_update_service.domain.TemplateVersion;
import com.template_update_service.domain.VersionPair;
import java.time.Instant;
import java.util.UUID;

/**
 * Sealed so the consumer's switch is checked for exhaustiveness by the compiler: a
 * fifth event type breaks the build rather than falling into a default branch that
 * silently drops it. {@code eventId} is what makes a replay a no-op, {@code seq}
 * what makes a stale delivery detectable -- delivery is at-least-once and unordered.
 */
public sealed interface TemplateEvent {

    UUID eventId();

    /** Carries the per-engagement sequence the projection guards against. */
    sealed interface EngagementEvent extends TemplateEvent {
        UUID engagementId();

        long seq();
    }

    /** One type for both outcomes, so the decision log is appended in one place. */
    sealed interface DecisionEvent extends EngagementEvent {
        VersionPair versions();

        UUID actorId();

        DecisionType decision();
    }

    /**
     * No seq: this orders by version, already monotonic per template. An out-of-order
     * publish is handled by refusing to move the head backwards, not by the guard.
     */
    record TemplatePublished(UUID eventId, UUID templateId, TemplateVersion version,
                             String versionLabel, Instant publishedAt) implements TemplateEvent {}

    /**
     * The only event carrying firmId and templateId, which is why a decision event
     * arriving before it cannot be written at all -- see EventConsumer on parking.
     */
    record EngagementCreated(UUID eventId, UUID engagementId, UUID firmId, UUID templateId,
                             TemplateVersion version, long seq) implements EngagementEvent {}

    record TemplateUpdateApplied(UUID eventId, UUID engagementId, VersionPair versions,
                                 UUID actorId, long seq) implements DecisionEvent {
        @Override
        public DecisionType decision() {
            return DecisionType.APPLIED;
        }
    }

    record UpdateDeclined(UUID eventId, UUID engagementId, VersionPair versions,
                          UUID actorId, long seq) implements DecisionEvent {
        @Override
        public DecisionType decision() {
            return DecisionType.DECLINED;
        }
    }
}
