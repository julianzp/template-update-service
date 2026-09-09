# Engagement Template Update Notifications — targeted implementation

Part 2 of the Caseware take-home: one slice of the system described in
[`docs/design-document.md`](docs/design-document.md), built to be correct under the delivery
guarantees the design assumes rather than broad and untested. The slice is the **event
consumer, the projection it maintains, and the decision state machine**.

## Running it

```bash
./mvnw test
```

Requires Docker — the suite starts one PostgreSQL 16 container via Testcontainers and runs
Flyway against it. 40 tests, about 20 seconds from cold.

## What is here, and what is not

**In scope.** Consuming the four events, maintaining the projection and the version catalog,
appending the decision log, and the dashboard query that answers *which engagements have
pending updates*.

**Out of scope, deliberately.** The LLM summarization pipeline, the diff/classification
engine, UI, authentication, notification delivery, and actually applying template content.
No AWS SDK: the queue is an interface (`EventQueue`) that tests drive directly.

### Why `ChangeSummaryPort` is unimplemented

It is a port with no adapter on purpose. The pipeline behind it — deterministic diff
classification first, an LLM narration layer over that classification second — is the part of
the design that needs an eval harness and a human review step, and stubbing it convincingly
would say nothing true about whether the rest works.

What matters architecturally is that **the read side does not block on it**. The dashboard
query LEFT JOINs the summary, so a pending update is returned with
`ChangeSummary.Status.ABSENT` when no prose exists yet. Withholding the item until a summary
arrived would make the dashboard understate what is pending, which is the one thing it exists
to get right. `Status.ABSENT` is a first-class enum value rather than a null, so no call site
handles that case by accident.

## Two gaps in the event contract

Both are producer-side changes for Phase 1, when the event schemas are versioned with the
upstream teams. Neither is an oversight in this implementation — the contract as specified
cannot express either, and I would rather say so than quietly approximate them.

### 1. Decision events carry no reference to the summary shown

`update_decision.summary_id` exists and is always written null.

Decision 5 of the design document leans on this column directly: *"Every decision is appended
with who decided, when, between which versions, and **which summary row informed it**."* The
point is that a regulator asking why a firm declined an update gets not just the decision but
the evidence the practitioner was looking at when they made it — and because summaries are
never overwritten, that row still says what it said at the time.

`UpdateDeclined` and `TemplateUpdateApplied` carry `(eventId, engagementId, fromVersion,
toVersion, actorId, seq)`. There is nowhere for the summary reference to come from, and
inferring it after the fact — looking up whichever summary is current for the pair now — would
be worse than leaving it null: it would assert evidence that may not be what was displayed.
The UI knows which summary it rendered; the event needs a field to carry it.

### 2. Decision events carry no timestamp

`update_decision.decided_at` is ingest time, not decision time.

For a log whose purpose is defensibility, an approximated timestamp is materially weaker
evidence. The gap is normally seconds, but it is unbounded: a redrive after a consumer outage
could put it hours out, and nothing in the row would indicate that. The producer should send
`decidedAt`.

### 3. No event closes an engagement

Rule 6 says closed and archived engagements never surface pending updates, and the query
enforces it. But creation, apply and decline are the only three events, so nothing in the
contract ever moves an engagement out of `active`. The status arrives with seeding (Phase 2)
and is never updated afterwards. Either the engagement management system emits a lifecycle
event, or the reconciliation job that already samples for drift maintains it.

## How correctness is achieved

**Idempotency.** A `processed_event` inbox keyed by `event_id`, written in the same
transaction as the projection update it guards. The inbox is checked first and written last:
writing it first and rolling back for a non-applied outcome would mean marking the transaction
rollback-only, which turns an ordinary control-flow result into an exception at commit.

**Ordering.** A monotonic `last_event_seq` per engagement, enforced as a `WHERE` clause on the
upsert rather than a read-then-write, so the database arbitrates and two concurrent deliveries
cannot interleave into a lost update. The comparison is strictly `<`: an equal sequence is a
redelivery, not progress. SQS FIFO grouped by engagement id is defence in depth — every test
here drives the queue interface directly and passes without it.

**Parking.** Decision events carry no `firmId` or `templateId`, and both are `NOT NULL`. A
decision arriving before its `EngagementCreated` therefore cannot be written at all, so it
returns `PARKED`, is deliberately kept out of the inbox, and is requeued. That absence from
the inbox is what makes redelivery correct.

**Single HEAD per template.** A partial unique index on `is_latest`, not application logic. A
version arriving out of order is stored but does not take the head.

**Append-only decisions.** `UPDATE` and `DELETE` are revoked from `template_update_app` at the
role level. Migrations run as the owner, which keeps full rights; the application connects as
a member of that role, which is where the restriction bites. The test drops to that role with
`SET ROLE` to observe it.

## Tests

| Test | What it pins down |
|---|---|
| `AccumulationStateMachineTest` | **The central case.** Created at v1 → publish v2 → pending `v1→v2` → decline → nothing pending → publish v3 → **reappears as `v1→v3`**, with the earlier decline row present and identical in every column. Plus: four versions accumulate to one item, not a queue. |
| `OutOfOrderConvergenceTest` | All 24 orderings of that same four-event batch converge on the same state, exhaustively rather than sampled. Plus a direct test of parking and redelivery. |
| `IdempotencyTest` | Every event delivered four times; state identical, every replay reports `DUPLICATE`. |
| `SequenceGuardTest` | Stale rejected, equal sequence rejected, a decline still advances the sequence, an out-of-order publish does not take the head. |
| `DashboardQueryTest` | Closed and archived never surface; a pending item is returned with no summary; a summary is joined when present; a superseded summary is not. |
| `AppendOnlyDecisionLogTest` | Under the application role, `UPDATE` and `DELETE` on the decision log fail while `INSERT` succeeds. |

The two guards were checked by mutation: relaxing `<` to `<=` fails `SequenceGuardTest`, and
dropping the exact-pair predicate from the decline suppression fails 24 of the ordering tests.

## One schema defect found while testing

The first version of `change_summary` keyed the "current summary per pair" unique index on
`superseded_by is null`, with `superseded_by` a foreign key. That combination makes Decision 5
**impossible to perform**: the pointer can only be set once the successor row exists, and the
successor cannot be inserted while the predecessor still looks current. Both orderings fail.

Fixed by splitting the two facts apart — `superseded_at` records that a row is no longer
current, `superseded_by` records what replaced it — so the sequence is retire, insert, then
link, inside one transaction. `DashboardQueryTest.aSupersededSummaryIsNotJoinedAndTheReplacementIs`
performs exactly that sequence.

## Notes on the implementation

Java 21, Spring Boot 4.1.1 (the version the scaffold was generated with), PostgreSQL, Flyway,
Testcontainers. `JdbcClient` rather than JPA: this is a projection — upserts, a conditional
head flip, one read query — and plain SQL keeps the ordering guard a single statement the
database arbitrates instead of application logic wrapped in a transaction.

`ChangeSummaryPort.findByPair` takes a `VersionPair` rather than the brief's literal
`(UUID, int, int)`. The brief also asks that raw ints not leak, and at that boundary the two
instructions collide: a bare `(int, int)` is exactly the leak, since nothing stops a caller
passing `(to, from)`. A `VersionPair` cannot be constructed backwards.

`EventPoller` is not a Spring bean. No `EventQueue` implementation ships in this slice, so
nothing could inject one; it is constructed by whatever supplies a queue — the tests here, an
SQS adapter in production.
