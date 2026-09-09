-- Read model rebuilt from events. Nothing here is a system of record: the
-- engagement management system and the template database remain authoritative,
-- so every table below must be reconstructible by replaying the event stream.

-- Inbox. Written in the same transaction as the projection update it guards,
-- which is what makes at-least-once delivery a no-op on replay.
create table processed_event (
    event_id     uuid        primary key,
    event_type   text        not null,
    aggregate_id uuid        not null,
    processed_at timestamptz not null default now()
);

-- Shared across firms: the same product templates serve every customer, so a
-- version pair means the same thing everywhere and carries no firm_id.
create table template_version_catalog (
    template_id     uuid        not null,
    version_seq     integer     not null,
    version_label   text        not null,
    published_at    timestamptz not null,
    is_latest       boolean     not null default false,
    source_event_id uuid        not null,
    primary key (template_id, version_seq),
    constraint template_version_seq_positive check (version_seq > 0)
);

-- The single-HEAD invariant, enforced by the database rather than by consumer
-- logic: an out-of-order TemplatePublished cannot leave two heads behind.
-- NOTE (disagreement, implemented as specified): is_latest is derivable as
-- max(version_seq) per template, and derived state cannot drift while a
-- maintained flag can. The design settles on the flag, and it does buy a
-- hard DB-level invariant plus an index-only head lookup, so it stands.
-- Consequence for the consumer: clear the old head before setting the new one
-- inside one transaction — a partial unique index cannot be deferred.
create unique index template_version_catalog_one_head
    on template_version_catalog (template_id)
    where is_latest;

-- One row per engagement: the whole point of the projection is that answering
-- "which engagements have pending updates" never loads an engagement file.
create table engagement_template_state (
    engagement_id     uuid        primary key,
    firm_id           uuid        not null,
    template_id       uuid        not null,
    applied_version   integer     not null,
    engagement_status text        not null default 'active',
    seeded_at         timestamptz not null default now(),
    seed_source       text        not null,
    last_event_id     uuid        not null,
    -- Monotonic per engagement. The ordering guard compares against this and
    -- rejects anything stale; FIFO by engagement id is defence in depth.
    last_event_seq    bigint      not null,
    updated_at        timestamptz not null default now(),
    constraint engagement_status_known
        check (engagement_status in ('active', 'closed', 'archived')),
    -- 'event' = seeded by EngagementCreated, 'backfill' = seeded by the
    -- Phase 2 loader. Surfaced to users as coverage, so it is not decoration.
    constraint seed_source_known
        check (seed_source in ('event', 'backfill'))
);

-- Serves the dashboard query. Partial, because closed and archived engagements
-- never surface pending updates and should not cost index maintenance.
create index engagement_template_state_pending_lookup
    on engagement_template_state (firm_id, template_id, applied_version)
    where engagement_status = 'active';
