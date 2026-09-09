package com.template_update_service.decision;

import com.template_update_service.events.TemplateEvent.DecisionEvent;
import com.template_update_service.projection.ProjectionRepository.EngagementRef;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DecisionLogRepository {

    private final JdbcClient jdbc;

    DecisionLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert only. The role this runs as holds no UPDATE or DELETE here, so an append
     * that turns out to be wrong is corrected by a later row, never by a rewrite.
     *
     * <p>Two gaps in the event contract, carried honestly rather than papered over:
     * decision events have no timestamp, so decided_at is ingest time and not when the
     * practitioner actually decided; and they carry no reference to the summary shown,
     * so summary_id stays null. Both want a producer-side change -- an audit trail that
     * approximates its own timestamps is weaker evidence than one that does not.
     */
    public void append(DecisionEvent event, EngagementRef ref) {
        jdbc.sql("""
                insert into update_decision (
                        id, engagement_id, firm_id, template_id, from_version, to_version,
                        decision, decided_by, decided_at, summary_id, source_event_id)
                values (:id, :engagementId, :firmId, :templateId, :fromVersion, :toVersion,
                        :decision, :decidedBy, now(), null, :eventId)
                """)
                .param("id", UUID.randomUUID())
                .param("engagementId", event.engagementId())
                .param("firmId", ref.firmId())
                .param("templateId", ref.templateId())
                .param("fromVersion", event.versions().from().seq())
                .param("toVersion", event.versions().to().seq())
                .param("decision", event.decision().dbValue())
                .param("decidedBy", event.actorId())
                .param("eventId", event.eventId())
                .update();
    }
}
