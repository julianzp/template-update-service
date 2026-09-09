package com.template_update_service.summary;

import com.template_update_service.domain.VersionPair;
import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately unimplemented: the pipeline behind it -- deterministic diff
 * classification first, LLM narration over that classification second -- is out of
 * scope for this slice. The read side must not block on it. A pending update whose
 * summary does not exist is returned with {@link ChangeSummary.Status#ABSENT}, never
 * withheld: withholding would make the dashboard understate what is pending, which is
 * the one thing it exists to get right.
 *
 * <p>DEVIATION from the brief, which writes {@code findByPair(UUID, int, int)}. The
 * brief also asks that raw ints not leak, and here the two rules collide: a bare
 * {@code (int, int)} is precisely that leak, since nothing stops a caller passing
 * {@code (to, from)}. A {@link VersionPair} cannot be constructed backwards.
 */
public interface ChangeSummaryPort {

    Optional<ChangeSummary> findByPair(UUID templateId, VersionPair versions);
}
