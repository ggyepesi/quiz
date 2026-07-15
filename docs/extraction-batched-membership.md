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

Steps 1–4 + 7 = stage 1; step 5 = stage 2; step 6 = stage 3. All gated behind the
existing "large membership" trigger; small classes keep the single-query path,
byte-identical.

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
  captured field(s); if nothing remains inlined, the enrichment is skipped (step 7).

### Stage 2 — remaining inlined entity fields (`type` = P31) via batched value-queries (DONE)

For each still-inlined entity-list field, member-batched
(`RuleNodeQueryBuilder.memberFieldBatchQuery` + `RuleTreeExtractor.captureMemberFields`):

- `SELECT DISTINCT ?value ?fieldValue WHERE { VALUES ?value { <member batch of
  memberFieldBatchSize=100> } ?value wdt:P31 ?fieldValue }` — `DISTINCT` is fine when
  cheap; `merge` onto the canonical registry member is the final duplicate guard
  (dedup + insertion order). The field's own direction places `?value` on the correct
  end, and a field type constraint (`membershipQid`) is emitted so values match the
  old enrichment.
- Best-effort per batch (a failed batch is warned + skipped; members stay complete).
- **Step 7**: once every inlined entity-list field is target-captured (stage 1) or
  member-batched (stage 2), the leftover enrichment node has no inlined field and
  `fieldOptimizedValuesQuery` is **not issued at all**. (A large membership with
  leftover *scalar* fields has no member-batched path yet — warned, not silently
  dropped; no model class hits this today.)

No `GROUP_CONCAT` over 11k; each batch is bounded.

### Stage 3 — labels resolved separately, via `wbgetentities`

- **Labels (DONE)** — the QID-only captures (stage-1 targets, stage-2 fields) create
  entity refs named by their qid. `RuleTreeExtractor.resolveLabels` collects every
  registry object still carrying a placeholder name (blank or == qid), resolves them
  in one best-effort `WikidataApiClient.getEntities(qids, [])` pass (labels only,
  batched 50 — the reliable action API, no WDQS scan), and sets the names via
  `applyLabels`. A failure warns and leaves the qids. No-op when nothing is
  unlabeled. (`RuleTreeExtractorLabelTest`.)
- **Type via `wbgetentities` claims (DONE, 2b)** — `captureOutgoingFieldsViaApi`
  reroutes the OUTGOING direct entity field(s) (P31/`type` — the member's own claim)
  onto `getEntities(members, [P31])` (claims → type, `applyEntityClaims`), so the
  flaky ~112-serial-SPARQL P31 path (single batches hit 50–122s and timed out) is
  gone. An INCOMING direct field (something points TO the member — none today) has no
  claim on the member and stays on the member-batched SPARQL path
  (`captureMemberFields`). Best-effort: a failed pass warns, members stay complete.
- **Parallelized (DONE)** — `getEntities` fans its 50-QID batches over a small pool
  (6), per-batch best-effort with a short retry: a transient failure drops only that
  batch, never the whole pass. `languages=en|mul` + mul fallback so an item without an
  English label still resolves.

## Validated

Full regen: **11,181 members / 15,481 Nominations in ~121 s** (was ~1390 s / ~23 min),
`target` + `type` filled with names. The backbone is flat/hint-first/label-free; the
member `type` claims + all labels come from `wbgetentities`. Runtime is now dominated
by the separate qualifier-load / reification phase, not extraction.

## Safety

Extraction is count- and content-critical and can't be validated against WDQS
offline, so per stage:

- **Unit-test the generated SPARQL** (byte-level, like `RuleTreeCompilerParityTest`)
  and the assembly (`MembershipTargetCaptureTest`,
  `RuleTreeExtractorMembershipTargetTest`).
- **Regen canary**: confirm 11,181 / 15,48x *and* that the field now fills.

## Out of scope

- Small (non-large) memberships — unchanged, single query.
- The qualifier-load / reification — separate, already batched.
