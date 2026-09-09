package com.template_update_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.template_update_service.consumer.EventOutcome;
import com.template_update_service.domain.VersionPair;
import com.template_update_service.events.TemplateEvent.UpdateDeclined;
import com.template_update_service.query.PendingUpdate;
import com.template_update_service.support.AbstractProjectionTest;
import com.template_update_service.support.Scenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The central case. Accumulation is never a queue: an engagement on v1 with v2, v3 and v4
 * published has ONE pending item, v1 -> v4. A decline suppresses exactly the pair it was
 * made against, so a newer version brings the item back -- a new version is new information
 * the practitioner has not seen, not a decision they already made.
 */
class AccumulationStateMachineTest extends AbstractProjectionTest {

    @Test
    void aDeclinedUpdateReappearsAgainstTheNewTargetWhenANewerVersionIsPublished() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.published(1));
        consumer.handle(s.created(1, 1));

        // v2 published: one pending item, v1 -> v2.
        consumer.handle(s.published(2));
        assertThat(pendingFor(s))
                .singleElement()
                .extracting(PendingUpdate::versions)
                .isEqualTo(VersionPair.of(1, 2));

        // Declined: nothing pending, because the decline names exactly this pair.
        UpdateDeclined decline = s.declined(1, 2, 2);
        assertThat(consumer.handle(decline)).isEqualTo(EventOutcome.APPLIED);
        assertThat(pendingFor(s)).isEmpty();
        List<Map<String, Object>> afterDecline = decisionRows();

        // v3 published: the item REAPPEARS as v1 -> v3, not v2 -> v3, and as one item rather
        // than two. The engagement never applied v2, so v1 is still where it sits.
        consumer.handle(s.published(3));
        assertThat(pendingFor(s))
                .singleElement()
                .extracting(PendingUpdate::versions)
                .isEqualTo(VersionPair.of(1, 3));

        // The earlier decline is still present and identical in every column: it suppressed
        // its own pair and nothing else, and nothing rewrote it when the situation changed.
        assertThat(decisionRows()).isEqualTo(afterDecline);
        assertThat(afterDecline).singleElement().satisfies(row -> {
            assertThat(row.get("from_version")).isEqualTo(1);
            assertThat(row.get("to_version")).isEqualTo(2);
            assertThat(row.get("decision")).isEqualTo("declined");
            assertThat(row.get("source_event_id")).isEqualTo(decline.eventId());
        });
    }

    @Test
    void versionsPublishedBeforeAnyDecisionAccumulateIntoASinglePendingItem() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));
        consumer.handle(s.published(3));
        consumer.handle(s.published(4));

        assertThat(pendingFor(s))
                .singleElement()
                .extracting(PendingUpdate::versions)
                .isEqualTo(VersionPair.of(1, 4));
    }

    @Test
    void applyingAnUpdateMovesTheEngagementForwardAndClearsThePendingItem() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));

        assertThat(consumer.handle(s.applied(1, 2, 2))).isEqualTo(EventOutcome.APPLIED);

        assertThat(pendingFor(s)).isEmpty();
        assertThat(projectionRow(s).get("applied_version")).isEqualTo(2);
    }
}
