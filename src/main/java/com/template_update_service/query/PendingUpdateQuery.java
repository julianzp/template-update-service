package com.template_update_service.query;

import com.template_update_service.domain.VersionPair;
import com.template_update_service.summary.ChangeSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PendingUpdateQuery {

    /**
     * Every domain rule in one statement: pending is applied &lt; latest (rule 2), the
     * item is always applied -&gt; head and never a queue (rule 3), a decline suppresses
     * that exact pair and no other (rule 4), and closed engagements never surface
     * (rule 6). The join to change_summary is outer because a pending update is real
     * whether or not anyone has written prose for it yet.
     */
    private static final String SQL = """
            select e.engagement_id, e.firm_id, e.template_id, e.applied_version,
                   c.version_seq as latest_version, c.version_label,
                   s.id as summary_id, s.status as summary_status, s.headline
              from engagement_template_state e
              join template_version_catalog c
                on c.template_id = e.template_id and c.is_latest
              left join change_summary s
                on s.template_id  = e.template_id
               and s.from_version = e.applied_version
               and s.to_version   = c.version_seq
               and s.superseded_by is null
             where e.firm_id = :firmId
               and e.engagement_status = 'active'
               and e.applied_version < c.version_seq
               and not exists (select 1
                                 from update_decision d
                                where d.engagement_id = e.engagement_id
                                  and d.from_version  = e.applied_version
                                  and d.to_version    = c.version_seq
                                  and d.decision      = 'declined')
             order by e.engagement_id
            """;

    private final JdbcClient jdbc;

    PendingUpdateQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PendingUpdate> findPendingForFirm(UUID firmId) {
        return jdbc.sql(SQL).param("firmId", firmId).query(PendingUpdateQuery::mapRow).list();
    }

    private static PendingUpdate mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PendingUpdate(
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("firm_id", UUID.class),
                rs.getObject("template_id", UUID.class),
                VersionPair.of(rs.getInt("applied_version"), rs.getInt("latest_version")),
                rs.getString("version_label"),
                rs.getObject("summary_id", UUID.class),
                ChangeSummary.Status.fromDb(rs.getString("summary_status")),
                rs.getString("headline"));
    }
}
