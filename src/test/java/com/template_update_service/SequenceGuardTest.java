package com.template_update_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.template_update_service.consumer.EventOutcome;
import com.template_update_service.support.AbstractProjectionTest;
import com.template_update_service.support.Scenario;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The guard, not the queue, is what makes ordering safe. FIFO by engagement id is defence in
 * depth: if it were ever misconfigured, lost, or replaced, these assertions would still hold.
 */
class SequenceGuardTest extends AbstractProjectionTest {

    @Test
    void anEventBehindTheHighWaterMarkChangesNothing() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.published(2));
        consumer.handle(s.created(1, 5));
        Map<String, Object> before = projectionRow(s);

        assertThat(consumer.handle(s.applied(1, 2, 3))).isEqualTo(EventOutcome.STALE);

        assertThat(projectionRow(s)).isEqualTo(before);
        assertThat(decisionRows()).as("a stale decision is never appended").isEmpty();
    }

    @Test
    void anEventLevelWithTheHighWaterMarkIsRejectedToo() {
        // The guard is strictly <. An equal sequence is a redelivery, not progress -- which
        // is what lets the sequence number back up the inbox against concurrent duplicates.
        Scenario s = Scenario.fresh();
        consumer.handle(s.published(2));
        consumer.handle(s.created(1, 5));

        assertThat(consumer.handle(s.applied(1, 2, 5))).isEqualTo(EventOutcome.STALE);
        assertThat(projectionRow(s).get("applied_version")).isEqualTo(1);
    }

    @Test
    void aDeclineStillAdvancesTheSequenceEvenThoughItDoesNotMoveTheVersion() {
        // Monotonicity has to hold across all three engagement event types, or a later replay
        // of an earlier event slips past the guard.
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));
        consumer.handle(s.declined(1, 2, 7));

        assertThat(projectionRow(s).get("last_event_seq")).isEqualTo(7L);
        assertThat(projectionRow(s).get("applied_version")).isEqualTo(1);
    }

    @Test
    void aVersionPublishedOutOfOrderDoesNotTakeTheHead() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(3));
        consumer.handle(s.published(2));

        // v2 is legitimate content and is stored, but v3 remains the head.
        assertThat(pendingFor(s)).singleElement()
                .satisfies(item -> assertThat(item.versions().to().seq()).isEqualTo(3));
        assertThat(jdbc.sql("select version_seq from template_version_catalog where is_latest")
                .query(Integer.class).single()).isEqualTo(3);
    }
}
