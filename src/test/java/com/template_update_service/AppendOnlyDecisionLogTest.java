package com.template_update_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.template_update_service.support.AbstractProjectionTest;
import com.template_update_service.support.Scenario;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Append-only is a privilege, not a convention. Tests connect as the owner, who keeps full
 * rights, so this deliberately drops to the role the application actually runs as -- which is
 * the only place the revoke can be observed.
 */
class AppendOnlyDecisionLogTest extends AbstractProjectionTest {

    @Test
    void theApplicationRoleMayAppendButNeverRewriteOrDelete() throws SQLException {
        Scenario s = Scenario.fresh();
        consumer.handle(s.created(1, 1));
        consumer.handle(s.published(2));
        consumer.handle(s.declined(1, 2, 2));
        assertThat(decisionRows()).hasSize(1);

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("set role template_update_app");

                assertThatThrownBy(() -> stmt.executeUpdate("update update_decision set decision = 'applied'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");

                assertThatThrownBy(() -> stmt.executeUpdate("delete from update_decision"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");

                // Appending is the one thing the role can do, so a correction is a new row.
                stmt.executeUpdate("""
                        insert into update_decision values (gen_random_uuid(), gen_random_uuid(),
                            gen_random_uuid(), gen_random_uuid(), 1, 2, 'applied',
                            gen_random_uuid(), now(), null, gen_random_uuid())
                        """);
            } finally {
                // Pooled connections are not reset when returned, so leaving the role set
                // would leak into whichever test borrows this connection next.
                stmt.execute("reset role");
            }
        }

        assertThat(decisionRows()).hasSize(2);
    }

    @Test
    void theInboxIsInsertOnlyForTheApplicationRoleToo() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("set role template_update_app");
                assertThatThrownBy(() -> stmt.executeUpdate("delete from processed_event"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            } finally {
                stmt.execute("reset role");
            }
        }
    }
}
