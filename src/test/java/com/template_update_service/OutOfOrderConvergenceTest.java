package com.template_update_service;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import com.template_update_service.consumer.EventOutcome;
import com.template_update_service.domain.VersionPair;
import com.template_update_service.events.TemplateEvent;
import com.template_update_service.events.TemplateEvent.DecisionEvent;
import com.template_update_service.events.TemplateEvent.EngagementCreated;
import com.template_update_service.events.TemplateEvent.TemplatePublished;
import com.template_update_service.events.TemplateEvent.UpdateDeclined;
import com.template_update_service.query.PendingUpdate;
import com.template_update_service.support.AbstractProjectionTest;
import com.template_update_service.support.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The batch is the accumulation case itself, so this asserts the central case survives every
 * possible delivery order -- all 24 of them, exhaustively rather than sampled. Orderings that
 * put the decline ahead of the creation it depends on park and are redelivered; they must
 * still land in the same place as the order the producer intended.
 */
class OutOfOrderConvergenceTest extends AbstractProjectionTest {

    private static final Scenario S = Scenario.fresh();
    private static final List<TemplateEvent> BATCH =
            List.of(S.created(1, 1), S.published(2), S.declined(1, 2, 2), S.published(3));

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyOrdering")
    void everyDeliveryOrderConvergesOnTheSameState(String ordering, List<TemplateEvent> events) {
        deliverUntilQuiescent(events);

        assertThat(pendingFor(S))
                .as("pending item after %s", ordering)
                .singleElement()
                .extracting(PendingUpdate::versions)
                .isEqualTo(VersionPair.of(1, 3));

        assertThat(decisionRows())
                .as("decision log after %s", ordering)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("from_version")).isEqualTo(1);
                    assertThat(row.get("to_version")).isEqualTo(2);
                });

        assertThat(projectionRow(S).get("applied_version")).isEqualTo(1);
        assertThat(projectionRow(S).get("last_event_seq")).isEqualTo(2L);
    }

    @Test
    void aDecisionArrivingBeforeItsEngagementParksAndIsAppliedOnRedelivery() {
        // The mechanism the permutations above depend on, asserted directly: firm_id and
        // template_id live only on EngagementCreated, so this event cannot be written yet.
        Scenario s = Scenario.fresh();
        UpdateDeclined decline = s.declined(1, 2, 2);

        assertThat(consumer.handle(decline)).isEqualTo(EventOutcome.PARKED);
        assertThat(decisionRows()).isEmpty();
        assertThat(inboxSize())
                .as("a parked event stays out of the inbox, which is what makes redelivery work")
                .isZero();

        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));

        assertThat(consumer.handle(decline)).isEqualTo(EventOutcome.APPLIED);
        assertThat(decisionRows()).hasSize(1);
        assertThat(pendingFor(s)).isEmpty();
    }

    private int inboxSize() {
        return jdbc.sql("select count(*) from processed_event").query(Integer.class).single();
    }

    static Stream<Arguments> everyOrdering() {
        return permutationsOf(BATCH).stream()
                .map(ordering -> Arguments.of(
                        ordering.stream().map(OutOfOrderConvergenceTest::label).collect(joining(", ")),
                        ordering));
    }

    private static String label(TemplateEvent event) {
        return switch (event) {
            case TemplatePublished e -> "publish " + e.version();
            case EngagementCreated e -> "created " + e.version();
            case DecisionEvent e -> e.decision().name().toLowerCase() + " " + e.versions();
        };
    }

    private static <T> List<List<T>> permutationsOf(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> permutations = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> remaining = new ArrayList<>(items);
            T head = remaining.remove(i);
            for (List<T> tail : permutationsOf(remaining)) {
                List<T> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                permutations.add(permutation);
            }
        }
        return permutations;
    }
}
