# One Parameterized Generation Pipeline

## Status

Design proposal. This document describes how Generate domain, Generate class preview,
Sample, Enrich and Remap should become parameterizations of one compiled pipeline. It
does not require old snapshots to be migrated and does not authorize automatic model
changes.

The immediate forcing reason is not merely duplicated methods. The five flows currently
describe similar phases separately, can compile the same model more than once, and do
not always begin from the same shape of graph. Consequently a preview can omit modeled
construction, Enrich must manually reproduce downstream ordering, and Remap has a
different capability before and after application restart.

This design builds on the existing `GenerationPipeline`, `GenerateDomainPipeline`,
`GenerationRun`, `RemapState`, `SourceExecutionPlan`, `SemanticConvergence`, shared
process workflow and batch/checkpoint mechanisms. It should factor those owners together,
not introduce a second generation implementation.

## Decision

There is one generation pipeline. A run is selected by explicit input, scope,
acquisition permission, limits and output disposition:

```text
Pipeline request
      |
      v
Compile one execution plan
      |
      v
Select and validate a starting checkpoint
      |
      v
Execute required phases; explain skipped phases
      |
      v
Produce one GenerationRun
      |
      v
Preview --------------------> explicit Apply
```

Generate domain, Generate class preview, Sample, Enrich and Remap are named request
factories for this pipeline. They are not separate implementations and are not encoded
as a large mode switch.

## The independent run dimensions

The current commands are combinations of four decisions:

1. **Input** — where the graph comes from and which processing stage it represents.
2. **Scope** — which population and production chain this run may operate on.
3. **Acquisition** — whether external facts may be requested.
4. **Output** — how the result is presented and whether it may replace the current run.

| Flow | Starting point | Population scope | Acquisition | Transformation |
|---|---|---|---|---|
| Generate domain | empty graph | all producible classes | all required facts | complete |
| Generate class preview | empty graph | one bounded production chain | allowed, bounded | complete |
| Sample | empty graph | one class, very small bound | allowed, tightly bounded | complete |
| Enrich | saved graph | existing population | missing facts only | rebuild affected results |
| Remap | best saved/cached graph | existing population | forbidden | local reconstruction only |

The public API should be readable without exposing those combinations as booleans:

```java
PipelineRequest.generateDomain(...)
PipelineRequest.generateClassPreview(...)
PipelineRequest.sampleClass(...)
PipelineRequest.enrich(...)
PipelineRequest.remap(...)
```

The factories only construct immutable request data. They do not contain alternate
execution sequences.

## Request model

Conceptually:

```text
PipelineRequest
  model
  input
  scope
  acquisition
  limits
  output
```

### Input

```text
EMPTY
SAVED_GRAPH
NORMALIZED_CHECKPOINT
CONSTRUCTED_CHECKPOINT
```

Input is not only a collection of objects. It names what has already happened to those
objects, because reifying an already-reified graph or treating a final snapshot as raw
source output is incorrect.

### Scope

```text
WHOLE_DOMAIN
CLASS_PRODUCTION_CHAIN(class)
EXISTING_POPULATION
```

Scope owns population bounds, not individual acquisition or transform stages. A class
sample follows the production chain required to make that class; it must not pretend a
derived, statement, aggregate or owned class can be sampled as an unrelated root.

### Acquisition

```text
ALL_REQUIRED
MISSING_ONLY
NONE
```

This is a permission and demand policy. It is not a promise that a request will occur.
The compiled demand plan and checkpoint coverage determine the actual work.

### Limits

Limits are explicit values such as sample size, class limit, traversal depth and
request/resource budgets. A preview is bounded through these values rather than by
silently omitting semantic phases.

### Output

```text
PREVIEW
REPLACEMENT_CANDIDATE
```

Every interactive run is inspectable before it mutates application state. Applying a
replacement remains an explicit UI action.

## Typed graph checkpoints

The largest obstacle to honest unification is that a `List<WikidataDynamicObject>` does
not say what it contains. The pipeline needs a typed checkpoint:

```text
GraphCheckpoint
  stage
  objects
  loaded declarations
  retained source evidence
  graph-discovery ledger
  quality
  model fingerprint
```

Initial checkpoint stages:

```text
NORMALIZED_SOURCE_GRAPH
CONSTRUCTED_GRAPH
FINAL_GRAPH
```

The names describe semantic state, not storage location. A checkpoint may be in memory
or restored from a snapshot.

`GenerationRun.RemapState.enrichedPool()` is the current partial form of a normalized
checkpoint. It enables full reconstruction while it remains in memory. After restart,
only a later-stage graph may be available, so Remap can safely run fewer transformations.
The first refactor must represent that limitation explicitly rather than pretending the
two cases are equivalent:

```text
Remap capability
  FULL_RECONSTRUCTION
  IDEMPOTENT_ONLY
```

The UI and query log must state which capability was planned and why. Persisting an
additional normalized checkpoint is a later decision, forced by measured value rather
than assumed by this refactor.

## One phase vocabulary

Every request is explained using the same ordered phases:

```text
1. Compile
2. Stage input graph
3. Discover population
4. Acquire source facts
5. Construct modeled records
6. Resolve semantic worklist
7. Acquire remaining external evidence
8. Hydrate names
9. Finalize and validate
10. Materialize instances
```

The compiled run plan marks each phase:

```text
RUN       required and permitted
SKIP      unnecessary, with a reason
BLOCKED   required but impossible under this request
```

For example, Remap should display the common pipeline rather than a separately authored
short diagram:

```text
Compile                       RUN
Stage input graph             RUN
Discover population           SKIP — using existing population
Acquire source facts          SKIP — acquisition forbidden
Construct modeled records     RUN or limited by checkpoint stage
Resolve semantic worklist     RUN
External evidence             SKIP — acquisition forbidden
Hydrate names                 RUN locally or SKIP with reason
Finalize and validate         RUN
Materialize instances         RUN
```

This replaces `configured(...)`, `configuredRemap(...)` and `configuredEnrich(...)`
with one description derived from the same compiled plan that execution consumes.

## Compiled execution plan

The model and datasource bindings are compiled exactly once, before external work:

```text
CompiledPipelineRun
  request
  compiled model
  compiled datasource plan
  fact demands
  ordered phase decisions
  starting checkpoint capability
```

Neither the UI nor an individual phase re-derives these decisions. Preview diagrams,
logs and execution consume the same object.

Each executable step declares:

```text
requires artifacts/stage
produces artifacts/stage
whether network access is possible
whether it supports the input checkpoint
human explanation
execute(context, state)
```

The planner validates all requirements before the first request. A malformed model,
missing datasource capability or insufficient checkpoint therefore becomes an explained
`BLOCKED` decision rather than a late exception or wasted acquisition.

## Shared execution state

Every step receives one context:

```text
PipelineContext
  compiled run
  datasource services
  cancellation
  generation log
  quality tracker
  fact store
  batch/checkpoint services
```

and advances one state:

```text
PipelineState
  current graph checkpoint
  produced roots
  statement records
  completed declarations
  graph-discovery coverage
  audits
  materialized instances
```

The existing owners remain responsible for their concepts. For example,
`SemanticConvergence` still owns its fixed point, the source execution plan still owns
resolved datasource operations, and the batch package still owns retry and request
checkpointing. The pipeline coordinates them; it does not reimplement them.

## Preview and Sample semantics

The invariant is:

> A preview may contain fewer instances, but every included instance is produced with
> the same semantics as full generation.

Therefore Generate class preview and Sample differ from Generate domain by scope and
limits, not by ad-hoc phase omissions. Applicable statement construction, keyed
reduction, owned composition, finalization and materialization still run.

If a phase genuinely cannot produce a faithful bounded result, the planner marks it
`SKIP` or `BLOCKED` with a visible reason. It must not quietly return a differently
shaped instance. `GenerationPipeline.fullRun()` should ultimately disappear; its meaning
becomes `run(PipelineRequest.generateClassPreview(...))`.

## Enrich semantics

Enrich is:

```text
input       = saved graph
scope       = existing population
acquisition = missing only
output      = replacement candidate
```

The planner computes missing demands before acquisition. Newly acquired facts invalidate
and rerun the downstream phases that depend on them:

```text
new fact
  -> construct, when statement/model structure depends on it
  -> semantic convergence
  -> external evidence dependencies
  -> names
  -> finalization
  -> materialization
```

This dependency belongs to the phase/artifact contract. Enrich must not maintain a
manually copied ordering that has to be updated whenever a new acquisition family is
introduced.

## Remap semantics

Remap is:

```text
input       = best available checkpoint
scope       = existing population
acquisition = none
output      = replacement candidate
```

With a normalized checkpoint it performs complete local reconstruction. With only a
final graph it executes only operations proven safe on that stage and reports
`IDEMPOTENT_ONLY`. Thus Remap and Enrich primarily differ in acquisition permission;
they do not need different coordinators.

## Quality and history

Failure events remain historical; quality describes the final state. A later phase or
iteration may satisfy a demand that failed earlier. The common pipeline therefore feeds
one `GenerationQualityTracker` and derives quality after the final graph is known.

Phase status is per run and checkpoint stage. It must not be inferred from whether a
historical request failed or whether a collection happens to be empty.

## Migration plan

### Milestone 0 — characterize the five flows — **DONE**

Recorded by `PipelineFlowsCharacterizationTest`, which reads each flow's phase order
from the source, because that is where the order is written and these flows need a
network to run. What it found:

| flow | order today |
|---|---|
| Generate domain | compile → plan → extract → **construct** → acquire-statements → **semantic** → external-evidence → finalize → materialize |
| Enrich | compile → **semantic** → external-evidence → **construct** → finalize |
| Remap | compile → **construct** → owned-components-only → finalize |
| Generate class preview (`fullRun`) | compile → extract → external-evidence → construct → canonicalize → materialize |
| Sample | extract → acquire-statements → construct-reify → semantic → aggregate → materialize |

Five flows, five orderings. Three discrepancies to remove, and one invariant already
held:

1. **Construct vs semantic is inverted between Generate domain and Enrich.** Kinds
   settled before reification see different records than kinds settled after, so the two
   flows can disagree about what one model produces. One order is wrong. Converging them
   changes behaviour on whichever moves; the recommendation is to keep Generate domain's
   order, because it produced every saved snapshot whose counts are the comparison for
   everything else — but that is a decision to take deliberately, not in passing.
2. **Remap composes parts without the semantic worklist**, calling `OwnedComponents`
   directly. Composition depends on kinds, so this is the variation most likely to be a
   latent bug rather than an economy.
3. **The preview omits the semantic worklist entirely, and neither it nor Sample
   finalizes.** The preview says so in a comment and explains why; the design's answer is
   that a bounded run differs by scope and limits, never by dropping a phase. Sample
   gained the semantic worklist while this was being written (a sampled Oscars Nominee
   was never classified, so it never became a Person and its P31 field never loaded);
   finalization is what it still skips.

Held already: **Remap reaches no network** — no extract, no statement acquisition, no
external evidence.

Result counts and input graph shape are not yet recorded; they need a representative
run against saved domains and belong with Milestone 4's comparison.

### Milestone 1 — request and checkpoint vocabulary

- Add immutable `PipelineRequest`, scope/acquisition/output types and `GraphCheckpoint`.
- Adapt the existing `GenerationRun` and `RemapState` without changing execution.
- Make full versus idempotent-only Remap capability explicit.

### Milestone 2 — compile once

- Add `CompiledPipelineRun` as the sole owner of compiled model, datasource plan, fact
  demands and phase decisions.
- Compile and validate it before any external operation.
- Drive the explanatory diagram from its phase decisions.

### Milestone 3 — common executor and local tail

- Introduce the step/artifact contract and common executor.
- Route finalization and materialization through it first.
- Then route construction and semantic convergence, reusing their existing owners.

### Milestone 4 — Generate domain

- Move Generate domain onto the executor without changing output.
- Compare instance counts, quality, graph coverage, source-yield reporting and query
  logs against a saved representative run.

### Milestone 5 — bounded production

- Express Generate class preview and Sample as bounded production scopes.
- Remove semantic omissions; where faithful bounded execution is impossible, expose the
  planner's reason.
- Verify that sampled instances have the same shape as their full-run counterparts.

### Milestone 6 — Enrich and Remap

- Route Enrich with `MISSING_ONLY` and dependency-driven downstream invalidation.
- Route Remap with `NONE` and checkpoint-dependent capability.
- Verify behavior both with an in-memory normalized checkpoint and after restoring a
  saved final graph.

### Milestone 7 — delete parallel orchestration

- Remove `fullRun()` and separately authored Generate/Enrich/Remap phase descriptions.
- Remove overloads whose only role was to reconstruct context or recompile plans.
- Keep named request factories as the user-facing vocabulary.

Deletion follows forcing tests and representative domain comparisons; it is not done in
advance merely to make the new abstraction appear complete.

## Acceptance criteria

- Every flow compiles exactly one `CompiledPipelineRun` before external work.
- One phase vocabulary and one compiled decision set drive both the diagram and execution.
- Every skipped phase has a visible, factual reason.
- Network work is impossible when acquisition is `NONE`.
- Enrich requests only demands not answered by its checkpoint and reruns every affected
  downstream phase.
- Remap states whether it is full reconstruction or idempotent-only.
- Preview and Sample differ from full generation only by declared scope and limits;
  included instances have the same semantics.
- Cancellation, quality, graph-discovery coverage and audits reach `GenerationRun`
  through the same state for all flows.
- No UI action applies a run without explicit confirmation.

## Non-goals

- Migrating old snapshots.
- Persisting a normalized checkpoint before measurements justify it.
- Replacing `SemanticConvergence`, datasource operations, the fact store or the batch
  executor.
- Introducing a general-purpose workflow language.
- Hiding flow differences behind booleans or a five-valued mode switch.
