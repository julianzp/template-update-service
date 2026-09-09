package com.template_update_service.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.template_update_service.TestcontainersConfiguration;
import com.template_update_service.consumer.EventConsumer;
import com.template_update_service.consumer.EventPoller;
import com.template_update_service.events.TemplateEvent;
import com.template_update_service.query.PendingUpdate;
import com.template_update_service.query.PendingUpdateQuery;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class AbstractProjectionTest {

    @Autowired
    protected EventConsumer consumer;

    @Autowired
    protected PendingUpdateQuery pendingUpdates;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DataSource dataSource;

    @BeforeEach
    void emptyTheReadModel() {
        jdbc.sql("""
                truncate processed_event, engagement_template_state,
                         update_decision, template_version_catalog, change_summary
                """).update();
    }

    protected List<PendingUpdate> pendingFor(Scenario scenario) {
        return pendingUpdates.findPendingForFirm(scenario.firmId());
    }

    /** Whole rows, so "unmodified" means every column rather than the ones a test remembered. */
    protected List<Map<String, Object>> decisionRows() {
        return jdbc.sql("select * from update_decision order by decided_at, id").query().listOfRows();
    }

    protected Map<String, Object> projectionRow(Scenario scenario) {
        return jdbc.sql("select * from engagement_template_state where engagement_id = :id")
                .param("id", scenario.engagementId())
                .query()
                .singleRow();
    }

    /**
     * Drains through the poller, so parked events get the redelivery the design depends on.
     * Bounded: a run that will not settle should fail the test, not spin.
     */
    protected void deliverUntilQuiescent(List<TemplateEvent> events) {
        InMemoryEventQueue queue = new InMemoryEventQueue(events);
        EventPoller poller = new EventPoller(queue, consumer);
        for (int round = 0; round <= events.size() && !queue.isDrained(); round++) {
            poller.drainOnce(events.size());
        }
        assertThat(queue.isDrained()).as("queue drained rather than stalling on a parked event").isTrue();
    }
}
