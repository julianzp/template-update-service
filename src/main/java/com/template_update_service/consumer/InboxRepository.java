package com.template_update_service.consumer;

import com.template_update_service.events.TemplateEvent;
import com.template_update_service.events.TemplateEvent.EngagementEvent;
import com.template_update_service.events.TemplateEvent.TemplatePublished;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InboxRepository {

    private final JdbcClient jdbc;

    InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean contains(UUID eventId) {
        return jdbc.sql("select 1 from processed_event where event_id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public void record(TemplateEvent event) {
        jdbc.sql("""
                insert into processed_event (event_id, event_type, aggregate_id)
                values (:id, :type, :aggregate)
                """)
                .param("id", event.eventId())
                .param("type", event.getClass().getSimpleName())
                .param("aggregate", aggregateOf(event))
                .update();
    }

    private static UUID aggregateOf(TemplateEvent event) {
        return switch (event) {
            case TemplatePublished e -> e.templateId();
            case EngagementEvent e -> e.engagementId();
        };
    }
}
