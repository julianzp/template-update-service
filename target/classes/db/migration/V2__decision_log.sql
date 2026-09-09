-- Append-only. The projection answers where an engagement is; only this log
-- answers who decided what, when, and on what basis. A regulator asking why a
-- firm declined an update across forty engagements is answered from here.
create table update_decision (
    id              uuid        primary key,
    engagement_id   uuid        not null,
    firm_id         uuid        not null,
    template_id     uuid        not null,
    from_version    integer     not null,
    to_version      integer     not null,
    decision        text        not null,
    decided_by      uuid        not null,
    decided_at      timestamptz not null,
    -- The summary row the practitioner actually saw. Nullable: a decision made
    -- on the deterministic fallback has no narrative row to point at.
    summary_id      uuid,
    -- Idempotency at rest. processed_event already stops the second delivery;
    -- this makes a duplicate insert impossible rather than merely unlikely.
    source_event_id uuid        not null unique,
    constraint decision_known check (decision in ('applied', 'declined')),
    constraint decision_moves_forward check (to_version > from_version)
);

-- Suppression lookup: is there a decline for this exact (from, to) pair?
-- A newer target misses this index entry, which is why the item reappears.
create index update_decision_pair
    on update_decision (engagement_id, from_version, to_version);

-- Deliberately no foreign keys from this table, or from
-- engagement_template_state, to the catalog or to each other. Delivery is
-- out of order: UpdateDeclined can legitimately arrive before the
-- EngagementCreated that introduces the engagement, and EngagementCreated can
-- arrive before the TemplatePublished for the version it was created on. An FK
-- would turn a normal ordering event into a dead letter.

-- This table exists so the read side has something to LEFT JOIN. The pipeline
-- that populates it -- diff classification, then LLM narration -- is out of
-- scope by design, not merely unbuilt: the dashboard must render a pending item
-- whose summary has not been generated yet, so the join stays outer and an empty
-- table is a supported state rather than a broken one. ChangeSummaryPort is the
-- seam where that pipeline will attach.
--
-- Shared across firms, like the catalog: templates are identical everywhere, so
-- one summary per version pair is computed once and reused by every firm.
create table change_summary (
    id             uuid        primary key,
    template_id    uuid        not null,
    from_version   integer     not null,
    to_version     integer     not null,
    status         text        not null,
    headline       text,
    -- Provenance, so a summary a practitioner acted on stays explainable.
    model_id       text,
    prompt_version text,
    generated_at   timestamptz not null default now(),
    -- Never overwritten. A new prompt writes a new row and points the old one
    -- at it, preserving the evidence behind decisions already made.
    superseded_by  uuid references change_summary (id),
    constraint summary_status_known
        check (status in ('pending', 'ready', 'failed'))
);

create unique index change_summary_current_pair
    on change_summary (template_id, from_version, to_version)
    where superseded_by is null;
