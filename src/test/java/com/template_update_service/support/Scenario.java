package com.template_update_service.support;

import com.template_update_service.domain.TemplateVersion;
import com.template_update_service.domain.VersionPair;
import com.template_update_service.events.TemplateEvent.EngagementCreated;
import com.template_update_service.events.TemplateEvent.TemplatePublished;
import com.template_update_service.events.TemplateEvent.TemplateUpdateApplied;
import com.template_update_service.events.TemplateEvent.UpdateDeclined;
import java.time.Instant;
import java.util.UUID;

/**
 * One firm, one template, one engagement -- enough for every case in the brief, and it
 * keeps the identifiers out of the tests so they read as the narrative they are testing.
 *
 * <p>Each call mints a fresh eventId, so a test that needs to replay <em>the same</em>
 * event must hold on to the instance rather than calling the factory twice.
 */
public record Scenario(UUID firmId, UUID templateId, UUID engagementId, UUID actorId) {

    public static Scenario fresh() {
        return new Scenario(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    public TemplatePublished published(int version) {
        return new TemplatePublished(UUID.randomUUID(), templateId, TemplateVersion.of(version),
                "v" + version, Instant.now());
    }

    public EngagementCreated created(int version, long seq) {
        return new EngagementCreated(UUID.randomUUID(), engagementId, firmId, templateId,
                TemplateVersion.of(version), seq);
    }

    public UpdateDeclined declined(int from, int to, long seq) {
        return new UpdateDeclined(UUID.randomUUID(), engagementId, VersionPair.of(from, to), actorId, seq);
    }

    public TemplateUpdateApplied applied(int from, int to, long seq) {
        return new TemplateUpdateApplied(UUID.randomUUID(), engagementId, VersionPair.of(from, to), actorId, seq);
    }
}
