# Batched-membership extraction restructure

## Problem

A large relational multi-target membership — e.g. `OscarNominations` = the P1411
nominees of ~58 Oscar categories (~11k members) — compiles to a single heavy root
query (`RuleNodeQueryBuilder.fieldOptimizedValuesQuery`). That query:

- inlines each entity-list field (`type` = P31, `target` = the categories) as a
  `GROUP_CONCAT` subquery, **with an inline `rdfs:label` FILTER** for each pair, and
- wraps the whole thing in an outer `SERVICE wikibase:label` over all ~11k members.

So it does ~4 passes over 11k rows (membership + P31 + a P1411 re-join for `target`
+ the label service) and overruns WDQS's 60s timeout → HTTP 200 with a **truncated
body** → JSON parse failure. The `§` a value shows (`…/Q5§human`) is just the
`QID§label` separator where the truncation landed.

### Already in place (transient/robustness)

- **Batched backbone** (`RuleTreeExtractor.runBackbone`): membership is fetched in
  target batches of 10 and unioned by qid — reliable complete member set.
- **Tolerated enrichment**: a failed field-optimized query no longer aborts the
  run; members survive (the union already guaranteed it), fields go unfilled.
- **Fail-fast on truncation**: a truncated partial-200 is non-retryable (it would
  just overrun again), so a doomed enrichment fails immediately.

Result: generation **completes with correct counts** (11,181 members → ~15,48x
Nominations from the separate qualifier-load), but the `type`/`target` **facet
fields are empty**.

## Goal

Fill `type`/`target` reliably by replacing the one heavy query with small batched
queries, none doing a `GROUP_CONCAT` + label-service over the full member set.
General — benefits any large relational membership, not just the Oscars.

## Consolidated pipeline

For a large relational membership:

1. Compile the membership into root batches (`membershipTargetBatchSize`, default 10).
2. Execute each batch as **relationship rows** — member qid + membership-target qid —
   in ONE query per batch (the backbone already binds `?value <pid> ?root`; it just
   selects `?root` too, no second request).
3. Assemble the **complete member registry** and the **membership-edge map**
   (member qid → insertion-ordered set of target qids).
4. Materialize any field **semantically equivalent to the membership target**
   directly from that edge map (canonical registry refs, deduped, in order).
5. Compile every **remaining** multivalued direct entity field into its own
   member-batched row query (`memberFieldBatchSize`, default 100).
6. Resolve all distinct qids through one shared best-effort **label cache**,
   preferably `wbgetentities` (`labelBatchSize`, default 500) — relationships come
   from SPARQL, entity labels from `wbgetentities`.
7. Do **not** issue `fieldOptimizedValuesQuery` when every requested field has been
   assigned to a specialized stage.

Operations 1–4 + 7 = Stage 1; operation 5 = Stage 2; operation 6 = Stage 3. All gated
behind the existing "large membership" trigger; small classes keep the single-query
path, byte-identical.

### Stage 1 — membership captures `target` (DONE)

- **Detection** (`RuleNodeQueryBuilder.membershipTargetFields` /
  `membershipEquivalent`): a field is a target only when it is provably the
  membership relation's object set — an **entity** reference, a **collection**, the
  **same normalized predicate**, and the **same emitted triple** (so the nominal
  `ROOT_TO_ITEM` vs `ITEM_TO_ROOT` swap is handled: the Oscars `target` is
  ROOT_TO_ITEM while the node is ITEM_TO_ROOT, yet both walk `?value P ?root`). A
  redundant field type constraint (target's `P31=Q19020`) is **subsumed**: capture
  binds the target to the explicit modeled root set, so it can never emit an
  out-of-set value — only correctly omit an unmodeled one. Value filters,
  allowed/excluded-QID filters and source backend are node-level (applied to
  `?value` in the same join the capture rides) and a field carries none, so those
  criteria hold by construction. **Semantics: bounded to the modeled roots** (a
  deliberate change from the old unbounded-`P31=Q19020` enrichment).
- **Capture** (`membershipBackboneQuery` → `flatBackboneQuery`): a FLAT, hint-first,
  label-free query — `SELECT DISTINCT ?value ?root WHERE { hint:Query hint:optimizer
  "None" . VALUES ?root {batch} ?value wdt:P1411 ?root . <filters> } LIMIT`. The hint
  binds the roots FIRST (P1411 is generic across Wikidata; without it Blazegraph scans
  the predicate and soft-times-out). No SERVICE label — QIDs only, members named in
  stage 3 — which is what removed the last inline label from the critical path.
- **Assemble + materialize** (`RuleTreeExtractor.runBackbone` →
  `MembershipBackbone{members, edges}` → `materializeMembershipTargets`): each
  `(member, root)` row adds an edge; each edge resolves to a canonical registry ref
  merged onto the member (dedup + insertion order).
- **Drop `target` from the enrichment** — a `sampleCopy` of the node minus the
  captured field(s); if nothing remains inlined, the enrichment is skipped (operation 7).

### Stage 2 — member field capture (DONE)

Populate the remaining inlined entity-list fields over the now-complete member set,
split by direction:

- **Outgoing** (the member's own claim, e.g. `type` = P31) → `wbgetentities` claims
  (`captureOutgoingFieldsViaApi` → `getEntities(members, [P31])` → `applyEntityClaims`).
  P31 via the SPARQL engine full-scans a hyper-common predicate and soft-times-out
  (the flaky ~112-serial-query path that hit 50–122 s per batch); the action API
  doesn't, and it returns the member's own label in the same response.
- **Incoming** (something points TO the member — none in the models today) has no
  claim on the member, so it stays on a member-batched SPARQL row query
  (`memberFieldBatchQuery` / `captureMemberFields`): `SELECT DISTINCT ?value
  ?fieldValue` over `VALUES ?value {batch of memberFieldBatchSize=100}` with a
  `hint:optimizer "None"` so the batch drives the join. Best-effort per batch; `merge`
  onto the canonical member is the final dedup guard.
- **Residual enrichment skipped** — once every inlined entity-list field is
  target-captured (Stage 1) or member-captured here, the leftover node has no inlined
  field and `fieldOptimizedValuesQuery` is not issued at all. (A large membership with
  leftover *scalar* fields has no member-batched path yet — warned, not silently
  dropped; no model class hits this today.)

### Stage 3 — label resolution, via `wbgetentities` (DONE)

The QID-only captures (Stage 1 targets, Stage 2 values) create entity refs named by
their qid. `resolveLabels` collects every registry object still carrying a placeholder
name (blank or == qid) and resolves it in one best-effort `getEntities(qids, [])` pass
(labels only, `languages=en|mul` + mul fallback). A failure warns and leaves the qids;
no-op when nothing is unlabeled. (`RuleTreeExtractorLabelTest`.)

`getEntities`/`getStatements` fan their 50-QID batches over a pool of 6, per-batch
best-effort with a short retry — a transient failure drops only that batch.

### Reification — unified onto `wbgetentities` (DONE)

The qualifier load (a *transform*, run after extraction) reifies each nominee's P1411
statements + qualifiers into Nominations. A `wbgetentities` claim already carries its
qualifiers, so `QualifierLoader` now reads them via `getStatements(nominees, P1411,
qualifierPids)` instead of a per-batch statement+qualifier SPARQL query — retiring the
halve-retry, 3 recovery rounds, value-anchored fallback and serial-apply lock that
papered over WDQS flakiness. One mechanism for extraction and reification.
(`QualifierLoaderReifyTest`.)

## Validated

- **Extraction**: 11,181 members, `target` + `type` filled with names, in ~121 s
  (was ~1390 s / ~23 min).
- **Reification**: ~15,48x Nominations, years correct; whole generation ~159 s.

The backbone is flat/hint-first/label-free; member `type` claims, statement qualifiers
and all labels come from `wbgetentities`. (The member count drifts ±a few run-to-run —
the known non-deterministic P1411 membership, not this work.)

## Safety

Extraction is count- and content-critical and can't be validated against WDQS
offline, so per stage:

- **Unit-test the generated SPARQL** (byte-level, like `RuleTreeCompilerParityTest`)
  and the assembly (`MembershipTargetCaptureTest`,
  `RuleTreeExtractorMembershipTargetTest`).
- **Regen canary**: confirm 11,181 / 15,48x *and* that the field now fills.

## Out of scope

- Small (non-large) memberships — unchanged, single query.
- Deterministic membership order — the ±few-member P1411 run-to-run drift is a
  separate thread (an `ORDER BY` / two-sided-VALUES determinism pass).
