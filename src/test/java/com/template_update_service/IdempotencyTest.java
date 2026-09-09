package com.template_update_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.template_update_service.consumer.EventOutcome;
import com.template_update_service.events.TemplateEvent;
import com.template_update_service.query.PendingUpdate;
import com.template_update_service.support.AbstractProjectionTest;
import com.template_update_service.support.Scenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * At-least-once delivery means every event will eventually arrive more than once. The inbox
 * makes the second and every later arrival a no-op, whatever the event was.
 */
class IdempotencyTest extends AbstractProjectionTest {

    @Test
    void replayingEveryEventLeavesTheStateItAlreadyReached() {
        Scenario s = Scenario.fresh();
        List<TemplateEvent> stream = List.of(
                s.published(1), s.created(1, 1), s.published(2), s.declined(1, 2, 2), s.published(3));

        stream.forEach(consumer::handle);
        Map<String, Object> projection = projectionRow(s);
        List<Map<String, Object>> decisions = decisionRows();
        List<PendingUpdate> pending = pendingFor(s);

        for (int replay = 0; replay < 3; replay++) {
            for (TemplateEvent event : stream) {
                assertThat(consumer.handle(event))
                        .as("replay %d of %s", replay, event.getClass().getSimpleName())
                        .isEqualTo(EventOutcome.DUPLICATE);
            }
        }

        assertThat(projectionRow(s)).isEqualTo(projection);
        assertThat(decisionRows()).isEqualTo(decisions);
        assertThat(pendingFor(s)).isEqualTo(pending);
    }
}
