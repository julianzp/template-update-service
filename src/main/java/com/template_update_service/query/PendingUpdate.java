package com.template_update_service.query;

import com.template_update_service.domain.VersionPair;
import com.template_update_service.summary.ChangeSummary;
import java.util.UUID;

/**
 * One row of the dashboard. Carries the summary's status rather than requiring one:
 * ABSENT is a renderable state, not a reason to withhold the item.
 */
public record PendingUpdate(UUID engagementId, UUID firmId, UUID templateId,
                            VersionPair versions, String latestVersionLabel,
                            UUID summaryId, ChangeSummary.Status summaryStatus,
                            String headline) {}
