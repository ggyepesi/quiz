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
  input (empty, or the actual typed checkpoint)
  scope
  acquisition
  limits
  output
```

### Input

```text
PipelineInput
  Empty
  Checkpoint(GraphCheckpoint)
```

Input is not only a collection of objects. It names what has already happened to those
objects, because reifying an already-reified graph or treating a final snapshot as raw
source output is incorrect. The checkpoint itself owns its stage; the request must not
carry a parallel stage enum that can disagree with it.

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
  constructed records (an identity selection from objects)
  loaded declarations
  retained source evidence (on the normalized objects and loaded declarations)
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
8. Refresh derived values
9. Hydrate names
10. Finalize and validate
11. Materialize instances
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
Refresh derived values        RUN
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

Recorded by `PipelineFlowsCharacterizationTest`, which reads executable call markers
from each flow's source, excluding comments, because that is where the order is written
and these flows need a network to run. This is temporary characterization evidence;
Milestone 2 replaces it with the compiled phase decisions that execution itself
consumes. What it found:

| flow | order today |
|---|---|
| Generate domain | compile → plan → extract → acquire-statements → **construct records + refresh derived values** → semantic → external-evidence → finalize → materialize |
| Enrich | compile → semantic → external-evidence → **refresh derived values** → finalize |
| Remap | compile → **construct** → owned-components-only → finalize |
| Generate class preview (`fullRun`) | compile → extract → external-evidence → construct → canonicalize → materialize |
| Sample | extract → acquire-statements → construct-records → semantic → aggregate → materialize |

Five flows, five orderings. Three discrepancies to remove, and one invariant already
held:

1. **The apparent Construct/Semantic inversion combines two different operations.**
   Generate starts from normalized source data, so it must construct statement records
   before semantic work can see them. Enrich starts from an already-constructed saved
   graph and does not reify again; its `StatementTransforms.applyIdempotent` call is a
   refresh of aggregates, restrictions, inverts and projections after newly declared and
   external values have been acquired. The checkpoint therefore decides whether
   **construct records** runs; this is not a per-flow ordering option.

   A narrower, real discrepancy remains. Generate performs the replayable derived-value
   refresh inside `StatementTransforms.apply`, before semantic and external acquisition,
   and does not refresh again afterwards. Enrich refreshes after both. If either
   acquisition supplies an input to an aggregate, restriction, invert or projection, the
   flows can disagree. Milestone 2 must expose **construct records** and **refresh derived
   values** as separate decisions. The latter is scheduled from dependencies after its
   last contributing producer (or reported as the present Generate discrepancy until
   execution is unified), never selected by a `generate/enrich` ordering flag.
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

### Milestone 1 — request and checkpoint vocabulary — **DONE**

`PipelineRequest` (with `PipelineInput`, `Acquisition`, `Output`), `PipelineScope`,
`PipelineLimits` and `GraphCheckpoint` (with `Stage` and `RemapCapability`). Nothing
executes yet; this is the vocabulary the flows will be described in.

Decisions taken while writing it:

- **A scope is one value, one way of being scoped**, following `EntityBound`. Two
  independent fields would let a run name a class AND claim the whole domain, and
  something would have to rank them. `CLASS_PRODUCTION_CHAIN` names its class; the
  others may not.
- **Unbounded means the model's own limits, not the absence of limits.** A bounded run
  adds a bound; it never removes the ones the model already carries.
- **Two unanswerable requests are refused where they are made**: starting from an empty
  graph with acquisition `NONE` has no source at all, and starting from an empty graph
  scoped to the existing population has no population. Everything else is a run that
  does something, and what a phase can do under it is the planner's question.
- **Remap capability is derived from the checkpoint stage**, not stored. It was implicit
  in whether `RemapState` happened to be null: with the enriched pool in memory a Remap
  rebuilt everything, and after a restart it quietly did less.
  `GenerationRun.remapCheckpoint(graphDiscovery)` now says which it is —
  `NORMALIZED_SOURCE_GRAPH` → `FULL_RECONSTRUCTION`, otherwise `IDEMPOTENT_ONLY`.
- **`RemapState.enrichedPool` is a normalized source graph** under another name, which is
  why full reconstruction is possible from it. Recorded on both, adapted rather than
  replaced.
- **A checkpoint carries the fingerprint of the model that made it**
  (`DomainSave.signature`), and an unknown signature on either side makes no claim
  rather than answering "yes".
- **The checkpoint is the input.** `PipelineInput` is either empty or carries the actual
  `GraphCheckpoint`; there is no second input-stage enum to contradict
  `GraphCheckpoint.Stage`.
- **Depth zero remains a real bound.** It means “follow no child edges,” as it does in
  `RuleTreeExtractor`. “Use the model's configured limit” is represented separately as
  `AS_CONFIGURED` and invalid negative values are refused rather than reinterpreted.
- **Checkpoint state is not invented.** A `GenerationRun` checkpoint requires the
  current graph-discovery ledger explicitly and carries the run's quality. It may not
  silently substitute an empty ledger for state owned by the loaded snapshot/UI.

Not built, for want of a forcing reason: request/resource budgets, which the design
lists among limits but nothing in the code asks for yet.

### Milestone 2 — compile once — **IN PROGRESS**

Done: `PipelinePhase`, `PhaseDecision` and `CompiledPipelineRun`, which decides every
phase from the request alone. No flow name reaches the planner; there is no
generate/enrich order switch.

- **Construct records is derived from the input checkpoint.** Empty or
  `NORMALIZED_SOURCE_GRAPH` → RUN; `CONSTRUCTED_GRAPH` or `FINAL_GRAPH` → SKIP, because
  reifying again would build a second copy of every record. This is what dissolved the
  apparent Generate/Enrich inversion.
- **Refresh derived values is scheduled from dependencies.**
  `refreshDerivedValuesAfter()` returns the last phase that both produces field values
  and actually runs, so Generate and Enrich converge on `ACQUIRE_EXTERNAL_EVIDENCE`
  without anyone choosing, and a run that acquires nothing converges on the semantic
  worklist. `PipelinePhase.producesFieldValues()` is where that dependency is stated —
  a coarse first form: it names the phases that can supply a value, not yet the fields
  each transform consumes.
- **A phase that does not run carries a reason**, enforced in `PhaseDecision`: a silent
  skip is indistinguishable from a phase somebody forgot. A phase that runs may not
  carry one either — it explains itself by running.
- **An uncompilable model BLOCKS every phase after COMPILE**, with the validation report
  as the reason, before anything is fetched. So does a scope naming a class the model
  does not contain.
- **Sample and Generate domain produce identical phase decisions**, which is the design's
  invariant made checkable: they differ by scope and limits and by nothing else.

**Compile once is now true of the model.** Every production flow that executes a run —
Generate domain, Generate class preview, Sample, Enrich and Remap — reads
`CompiledPipelineRun.model()` instead of compiling for itself. Generate, Enrich and
Remap also carry the same compiled-run object from the UI description into the query. A
Generate run previously compiled the model twice, once to say what would happen and once
to make it happen, and the two could describe different models the moment anything edited
one between them. Each flow now also refuses a blocked plan before it fetches, with the
model's validation report as the reason. Generate performs this refusal before unit
lookup, which is itself an external request rather than harmless preparation.

Phase decisions are exhaustive: constructing a `CompiledPipelineRun` without one
decision per `PipelinePhase` is refused. An omitted entry can no longer become implicit
permission to run through a default.

The semantic worklist is not itself classified as a network phase. It always contains
local stamping, stored-evidence classification and owned composition; acquisition
permission controls the missing-fact operation inside it. This lets Remap run the local
semantic subset under `NONE` while still receiving no acquisition client.

`OneCompilePerRunTest` holds it, and states the distinction it rests on: an advisor
explaining a class, a panel deriving inverts and a transform deriving reifications each
compile for a question of their own and are not runs. What may not compile for itself is
a flow that executes one.

Still open in this milestone:

- The compiled datasource plan and fact demands are not yet owned here; today they are
  built inside the flows.
- The explanatory diagram is built from the compiled run but still enumerates its own
  phases in `configured(...)`, `configuredRemap(...)` and `configuredEnrich(...)`.
  `CompiledPipelineRun.explain()` derives one description from the decisions; switching
  the UI onto it changes what a reader sees — Generate would gain "Stage input graph"
  and "Refresh derived values" rows — so it is a deliberate step, not a tidy-up.
- `HYDRATE_NAMES` is SKIPped under acquisition `NONE`. Whether names can be hydrated
  locally from stored labels is unverified; the reason says what was assumed, and it
  should be checked rather than left as an assumption.

### Milestone 3 — common executor and local tail — **IN PROGRESS**

Done: `PipelineStep`, `PipelineState`, `PipelineContext`, `PipelineExecutor`, and the
local tail as steps — `FinalizeStep` and `MaterializeStep`. The steps reimplement
nothing: finalization is still `DomainFinalization`, materialization still
`GenerationPipeline.buildRuntime`/`materialize`. What is new is the contract they are
called through.

- **The order is `PipelinePhase`'s and belongs to nobody else.** Steps are registered in
  any order and run in the vocabulary's.
- **Three refusals, each before a step runs rather than during it**: a phase the plan did
  not mark RUN is not run and the plan's reason is reported; a step whose required stage
  the graph has not reached is refused; and a step that reaches the network under a run
  forbidden to acquire is refused even if a plan wrongly said RUN. The last is a second
  lock on the invariant the whole Remap flow rests on.
- **A context for a forbidden run is not given a client to acquire with.** "Reaches no
  network" becomes a property of what a step HAS, not of what it remembers not to call.
- **A stage is reached, not set.** `PipelineState.reached` refuses to move a graph
  backwards, so a step cannot quietly undo the stage another established.
- **Cancellation does not return an incomplete success.** The executor throws before
  the next step runs. A caller therefore cannot continue and dereference finalization or
  materialization artifacts which cancellation deliberately prevented.

**Generate domain's tail is routed.** `DomainFinalization` and materialization are
reached through `FinalizeStep` and `MaterializeStep`, over one `PipelineState`.

Two decisions worth recording:

- **Two executor calls, not one.** About 120 lines of reporting sit between the two
  phases in `GenerateDomainQuery`, and folding them into a single call would move what a
  reader sees mid-run. The ordering guarantee does not come from a single call: materialize
  requires a `FINAL_GRAPH` and only finalize leaves one, so calling them the wrong way
  round is refused rather than quietly mapping an unfinalized graph.
- **The state shares the flow's pool rather than copying it**, via `PipelineState.over`.
  Finalization prunes — dead stubs, orphans, records missing a required field — and a copy
  would prune the copy while the flow kept holding what was removed. A state built
  `from` a checkpoint makes one graph-preserving deep copy, because a checkpoint records
  what was and a run does not edit its own history. Its constructed-record selection is
  copied with the graph so every selected record remains the same object as its pool
  member after resume.

The characterization recognised the change: with the tail behind the executor,
`GenerateDomainQuery` no longer contains `DomainFinalization.apply` or `buildRuntime`. A
routed phase is now recognised by the step it registers — the record migrating, one phase
at a time, towards the decisions themselves.

**Network permission is per operation, not per phase.** `PipelineStep.networkUse()`
replaces the boolean with `NONE` / `OPTIONAL` / `REQUIRED`, and the executor refuses only
`REQUIRED` under acquisition `NONE`.

- `SemanticConvergence` now runs its LOCAL subset when it has no client — stamping roles,
  classifying kinds from stored evidence, composing owned parts. A run forbidden to
  acquire is not a run forbidden to think, and this is what lets a Remap converge instead
  of reaching past the worklist for `OwnedComponents` alone and skipping the two steps
  composition depends on. That removes the shortcut Milestone 0 recorded as a
  discrepancy, in principle; routing Remap onto the step is what makes it true in fact.
- The subset is chosen by what the context HAS, not by what a step remembers not to call:
  a forbidden run carries no client, so the acquiring half is unreachable.
- `ConstructRecordsStep` is `OPTIONAL` for the same reason — only companion matching can
  acquire, and the sets are already a supplier parameter of `StatementTransforms.apply`,
  so a run that may not acquire replays the ones it cached. That is what makes a Remap
  from a normalized graph a full reconstruction rather than a partial one.

**Generate is routed end to end.** Construction, the worklist, finalization and
materialization all run through the executor over ONE `PipelineState`, created where the
reify pool is and carried to the end. Generate's phases operate on the same list
throughout — `pool` IS `reifyPool`, and the worklist's roots are that pool — so one state
spans them without any phase needing a view of its own.

The steps return their whole result, not a count: the run reads the companion sets it
fetched (to cache for a later Remap), the self-references construction found, the records
a projection changed, and what the worklist settled or could not. A step that reported
only a number would make its caller re-derive the rest, which is how a second answer gets
into a run.

Still open: Remap's and Enrich's tails, and Remap onto `SemanticWorklistStep` — which is
what turns the removed shortcut from a capability into a fact, and changes what Remap
produces, so it wants Milestone 4's comparison rather than a claim.

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
