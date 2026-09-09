package com.template_update_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.template_update_service.domain.VersionPair;
import com.template_update_service.summary.ChangeSummary;
import com.template_update_service.support.AbstractProjectionTest;
import com.template_update_service.support.Scenario;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardQueryTest extends AbstractProjectionTest {

    @Test
    void aClosedOrArchivedEngagementNeverSurfacesAPendingUpdate() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));
        assertThat(pendingFor(s)).hasSize(1);

        // No event in the contract closes an engagement, so this writes the status directly,
        // which is what seeding does. See EngagementStatus and the README.
        setStatus(s, "closed");
        assertThat(pendingFor(s)).isEmpty();

        setStatus(s, "archived");
        assertThat(pendingFor(s)).isEmpty();
    }

    @Test
    void aPendingItemIsReturnedEvenWhenNoSummaryHasBeenGenerated() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));

        assertThat(pendingFor(s)).singleElement().satisfies(item -> {
            assertThat(item.versions()).isEqualTo(VersionPair.of(1, 2));
            assertThat(item.summaryStatus()).isEqualTo(ChangeSummary.Status.ABSENT);
            assertThat(item.summaryId()).isNull();
            assertThat(item.headline()).isNull();
        });
    }

    @Test
    void aSummaryIsJoinedOntoThePendingItemOnceItExists() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));
        UUID summaryId = insertSummary(s, 1, 2, "ready", "Three disclosure sections changed");

        assertThat(pendingFor(s)).singleElement().satisfies(item -> {
            assertThat(item.summaryId()).isEqualTo(summaryId);
            assertThat(item.summaryStatus()).isEqualTo(ChangeSummary.Status.READY);
            assertThat(item.headline()).isEqualTo("Three disclosure sections changed");
        });
    }

    @Test
    void aSupersededSummaryIsNotJoinedAndTheReplacementIs() {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));
        UUID earlier = insertSummary(s, 1, 2, "ready", "Written by an earlier prompt");

        // The supersession sequence a new prompt version performs, in the only order the
        // schema allows: retire the current row, insert its replacement, then record the
        // lineage. Doing it in one transaction is what keeps the pair continuously covered.
        UUID replacement = supersede(earlier, s, "Written by the current prompt");

        assertThat(pendingFor(s)).singleElement().satisfies(item -> {
            assertThat(item.summaryId()).isEqualTo(replacement);
            assertThat(item.headline()).isEqualTo("Written by the current prompt");
        });
        assertThat(jdbc.sql("select superseded_by from change_summary where id = :id")
                .param("id", earlier).query(UUID.class).single()).isEqualTo(replacement);
    }

    private void setStatus(Scenario scenario, String status) {
        jdbc.sql("update engagement_template_state set engagement_status = :status where engagement_id = :id")
                .param("status", status)
                .param("id", scenario.engagementId())
                .update();
    }

    private UUID insertSummary(Scenario scenario, int from, int to, String status, String headline) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into change_summary (id, template_id, from_version, to_version, status, headline)
                values (:id, :templateId, :from, :to, :status, :headline)
                """)
                .param("id", id)
                .param("templateId", scenario.templateId())
                .param("from", from)
                .param("to", to)
                .param("status", status)
                .param("headline", headline)
                .update();
        return id;
    }

    private UUID supersede(UUID current, Scenario scenario, String headline) {
        jdbc.sql("update change_summary set superseded_at = now() where id = :id")
                .param("id", current).update();
        UUID replacement = insertSummary(scenario, 1, 2, "ready", headline);
        jdbc.sql("update change_summary set superseded_by = :replacement where id = :id")
                .param("replacement", replacement).param("id", current).update();
        return replacement;
    }
}
