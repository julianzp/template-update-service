package com.template_update_service.projection;

import com.template_update_service.domain.DecisionType;
import com.template_update_service.events.TemplateEvent.DecisionEvent;
import com.template_update_service.events.TemplateEvent.EngagementCreated;
import com.template_update_service.events.TemplateEvent.TemplatePublished;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectionRepository {

    private final JdbcClient jdbc;

    ProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The two columns decision events do not carry. Empty means the event must park. */
    public Optional<EngagementRef> findRef(UUID engagementId) {
        return jdbc.sql("""
                select firm_id, template_id
                  from engagement_template_state
                 where engagement_id = :id
                """)
                .param("id", engagementId)
                .query((rs, row) -> new EngagementRef(rs.getObject("firm_id", UUID.class),
                        rs.getObject("template_id", UUID.class)))
                .optional();
    }

    /**
     * The guard is the WHERE on the conflict branch rather than a read-then-write, so
     * the database arbitrates and two concurrent deliveries cannot interleave into a
     * lost update. Strictly {@code <}: an equal sequence is a duplicate, not progress.
     *
     * @return rows affected; zero means the event was stale
     */
    public int applyCreation(EngagementCreated event) {
        return jdbc.sql("""
                insert into engagement_template_state (
                        engagement_id, firm_id, template_id, applied_version,
                        engagement_status, seed_source, last_event_id, last_event_seq, updated_at)
                values (:engagementId, :firmId, :templateId, :appliedVersion,
                        'active', 'event', :eventId, :seq, now())
                on conflict (engagement_id) do update
                   set firm_id         = excluded.firm_id,
                       template_id     = excluded.template_id,
                       applied_version = excluded.applied_version,
                       last_event_id   = excluded.last_event_id,
                       last_event_seq  = excluded.last_event_seq,
                       updated_at      = now()
                 where engagement_template_state.last_event_seq < excluded.last_event_seq
                """)
                .param("engagementId", event.engagementId())
                .param("firmId", event.firmId())
                .param("templateId", event.templateId())
                .param("appliedVersion", event.version().seq())
                .param("eventId", event.eventId())
                .param("seq", event.seq())
                .update();
    }

    /**
     * One statement carrying both the guard and the conditional advance. A decline
     * still moves last_event_seq: monotonicity has to hold across all three engagement
     * event types, or a later replay of an earlier event slips past the guard.
     *
     * @return rows affected; zero means the event was stale
     */
    public int applyDecision(DecisionEvent event) {
        return jdbc.sql("""
                update engagement_template_state
                   set applied_version = case when :advance then :toVersion else applied_version end,
                       last_event_id   = :eventId,
                       last_event_seq  = :seq,
                       updated_at      = now()
                 where engagement_id  = :engagementId
                   and last_event_seq < :seq
                """)
                .param("advance", event.decision() == DecisionType.APPLIED)
                .param("toVersion", event.versions().to().seq())
                .param("eventId", event.eventId())
                .param("seq", event.seq())
                .param("engagementId", event.engagementId())
                .update();
    }

    /**
     * A publish is never stale: a version arriving out of order is legitimate content,
     * it simply does not become the head.
     */
    public void recordPublication(TemplatePublished event) {
        jdbc.sql("""
                insert into template_version_catalog (
                        template_id, version_seq, version_label, published_at, is_latest, source_event_id)
                values (:templateId, :seq, :label, :publishedAt, false, :eventId)
                on conflict (template_id, version_seq) do nothing
                """)
                .param("templateId", event.templateId())
                .param("seq", event.version().seq())
                .param("label", event.versionLabel())
                .param("publishedAt", OffsetDateTime.ofInstant(event.publishedAt(), ZoneOffset.UTC))
                .param("eventId", event.eventId())
                .update();

        // Clear before setting: a partial unique index cannot be deferred, so the two
        // statements cannot be reordered without transiently violating single-HEAD.
        jdbc.sql("""
                update template_version_catalog set is_latest = false
                 where template_id = :templateId and is_latest and version_seq < :seq
                """)
                .param("templateId", event.templateId())
                .param("seq", event.version().seq())
                .update();

        // A late, lower version does not take the head: the clear above will not touch a
        // higher head, so this finds one still present and changes nothing.
        jdbc.sql("""
                update template_version_catalog set is_latest = true
                 where template_id = :templateId and version_seq = :seq
                   and not exists (select 1 from template_version_catalog
                                    where template_id = :templateId and is_latest)
                """)
                .param("templateId", event.templateId())
                .param("seq", event.version().seq())
                .update();
    }

    public record EngagementRef(UUID firmId, UUID templateId) {}
}
