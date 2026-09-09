package com.template_update_service.consumer;

import com.template_update_service.decision.DecisionLogRepository;
import com.template_update_service.events.TemplateEvent;
import com.template_update_service.events.TemplateEvent.DecisionEvent;
import com.template_update_service.events.TemplateEvent.EngagementCreated;
import com.template_update_service.events.TemplateEvent.TemplatePublished;
import com.template_update_service.projection.ProjectionRepository;
import com.template_update_service.projection.ProjectionRepository.EngagementRef;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventConsumer {

    private final InboxRepository inbox;
    private final ProjectionRepository projection;
    private final DecisionLogRepository decisions;

    EventConsumer(InboxRepository inbox, ProjectionRepository projection,
                  DecisionLogRepository decisions) {
        this.inbox = inbox;
        this.projection = projection;
        this.decisions = decisions;
    }

    /**
     * The inbox row is written in the same transaction as the projection update it
     * guards, which is what makes at-least-once delivery a no-op on replay.
     *
     * <p>The inbox is checked first and written last. Writing it first and rolling it
     * back for STALE or PARKED would mean marking the transaction rollback-only, which
     * turns a normal outcome into an UnexpectedRollbackException at commit. The window
     * this opens -- two concurrent copies of one event both passing the check -- is
     * closed three times over: the processed_event primary key, the sequence guard
     * rejecting the second copy's equal seq, and update_decision.source_event_id.
     */
    @Transactional
    public EventOutcome handle(TemplateEvent event) {
        if (inbox.contains(event.eventId())) {
            return EventOutcome.DUPLICATE;
        }
        EventOutcome outcome = switch (event) {
            case TemplatePublished e -> publish(e);
            case EngagementCreated e -> create(e);
            case DecisionEvent e -> decide(e);
        };
        if (outcome != EventOutcome.PARKED) {
            inbox.record(event);
        }
        return outcome;
    }

    private EventOutcome publish(TemplatePublished event) {
        projection.recordPublication(event);
        return EventOutcome.APPLIED;
    }

    private EventOutcome create(EngagementCreated event) {
        return projection.applyCreation(event) == 0 ? EventOutcome.STALE : EventOutcome.APPLIED;
    }

    private EventOutcome decide(DecisionEvent event) {
        Optional<EngagementRef> ref = projection.findRef(event.engagementId());
        if (ref.isEmpty()) {
            return EventOutcome.PARKED;
        }
        // Staleness is settled before anything is appended, so a reordered duplicate
        // never writes a row into a log that cannot be corrected by deletion.
        if (projection.applyDecision(event) == 0) {
            return EventOutcome.STALE;
        }
        decisions.append(event, ref.get());
        return EventOutcome.APPLIED;
    }
}
