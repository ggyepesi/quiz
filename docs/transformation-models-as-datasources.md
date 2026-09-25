# Transformation Models as Datasources

## Status

Design decision, not yet implemented. This document defines how ModelBuilder generation,
graph constraints, transformations, curation decisions, populations, and persistence become
one reproducible project build without automating one desktop application from the other.

The deliverable is not merely a transformed class that another editor can open. A complete
saved project configuration must be executable headlessly, with no desktop UI present, and
must produce the final servable domain output. ModelBuilder and TransformApp are editors and
inspectors for that configuration; neither is the execution boundary.

The worked case is:

```text
Historical Positions.Position              1,317 generated instances
        ↓ saved TransformApp configuration
Historical Positions.PositionWithHolders     357 materialized instances
        ↓ saved graph expansion through P1366 (replaced by)
Historical Positions.PositionWithHolders     357 seeds + accepted predecessors
        ↓ consumed by History
History.OfficeHolding                      P39 statements into the expanded positions
```

It complements [Models and Domains](models-and-domains.md),
[ModelBuilder Constructs](modelbuilder-constructs.md),
[Parameterized Generation Pipeline](parameterized-generation-pipeline.md), and
[Open-source Boundary](open-source-boundary.md).

## The decision

A transformed class is a real class whose population is **materialized by a saved
transformation model**. It is not:

- an ordinary subclass whose population is regenerated from its base class;
- merely a `PopulationSelection` containing the output QIDs;
- a detached TransformApp-only result; or
- a second copy of transformation controls inside ModelBuilder.

TransformApp edits and previews a provider-neutral transformation configuration. A shared,
headless transformation executor runs it. ModelBuilder sees a named transformation output
through a datasource adapter and may therefore request the class through the same compiled
source-plan boundary as any other population producer.

The transformation configuration remains the single source of truth for the transformation.
The project build configuration is the single source of truth for when that transformation,
generation, graph constraint, population publication, and final save run. A datasource recipe
contains only a stable reference to one output; it does not duplicate either configuration.

## Why the current subclass representation is insufficient

TransformApp currently persists a semantic subclass by naming its base and clearing its
independent population. That preserves the output schema but loses how its instances were
obtained. A later generator therefore reads:

```text
PositionWithHolders extends Position
no independent population source
```

and reasonably—but incorrectly—uses all inherited `Position` members. History imports only the
declaration and consequently generates 1,317.

Measured on 2026-09-23, every copy of this class's membership that exists, and the one place it
ought to exist:

```text
historicalpositions.model.json          PositionWithHoldersPopulation — 357 bare QIDs
data/wikidata/transform/
  historical-positions.snapshot.json    PositionWithHolders — 357 typed objects, detached (#272)
history.snapshot.json                   PositionWithHolders — 1,317, the whole base population
historicalpositions.snapshot.json       does not exist (#273)
```

The owning project has no instance snapshot at all, so there is currently nothing for a
materialized class to be materialized *by*. That is not a separate accident: a class whose
population is an output nobody stored is exactly the state this design has to make fail
loudly, and today it fails by quietly becoming 1,317 instead.

The lost fact is not “these 357 QIDs form a selection.” The lost fact is:

> `PositionWithHolders` is the output class of this transformation over these modeled input
> instances.

A population selection remains useful as an exact reusable identity set for graph starts and
statement bounds. It does not replace a transformed class carrying schema, fields, references,
lineage, and complete typed instances.

### Which of the two a bound names

Both constructs will then describe the same 357 offices, and they answer to different things:
the selection is a frozen identity set, the transformed class is the output of a transformation
that can be re-run. Today `History.OfficeHolding` bounds its object end by
`PositionWithHoldersPopulation`. Once `PositionWithHolders` is a materialized class, re-running
the transformation moves the class and leaves the selection where it was saved, and nothing
notices the disagreement — two routes to one fact, which is a latent bug while they still agree.

This has to be decided rather than left to whichever the editor offers first. The two
candidates:

- **A bound may name a transformed class**, and naming its class is what a bound over that
  population means. The selection stays for populations that are authored directly, not derived.
  Renaming or re-running the transformation reaches the bound, because the bound references the
  class declaration.
- **A bound always names a selection**, and a transformed class publishes one as an output.
  Uniform for every bound, but the published selection is a second representation that must be
  rewritten on every run, and a bound can still be pointed at a stale hand-made copy.

The first keeps one discovery path for a derived population and is the recommendation. Either
way, a selection whose members were produced by a transformation should say so, so that editing
it by hand is visibly editing a copy.

## Class instance handling

Every class has one explicit population handling mode:

```text
GENERATED
    Its population is obtained from its configured datasource recipe, statement source,
    graph traversal, owned-production site, aggregate, or inherited generation rule.

MATERIALIZED_TRANSFORM
    Its population is the named output of a saved transformation model. Its base class
    contributes schema only. Population membership is never inherited as a fallback.
```

The complete instances do not belong inline in `model.json`. “The model owns the instances”
means that the class declaration names its transformation output and the companion snapshot
stores the materialized object graph beneath the owning project directory.

If a materialized output is missing or stale, execution fails with the transformation name,
input class, output class, and exact missing/stale file. It must never silently generate the
base population.

## Saved transformation model

A transformation model is immutable execution configuration plus stable declaration
references. Conceptually:

```text
TransformationModel
  declarationId
  name
  inputs[]
    projectId / project name
    classDeclarationId / class name
    required snapshot stage
  operations[]
    filter
    group
    project/derive field
    join/reference traversal
    include/exclude saved selection
    manual decision set
  outputs[]
    classDeclarationId / class name
    base class reference
    output schema
    population handling = MATERIALIZED_TRANSFORM
```

Operations are ordered and typed. Their serialized form owns every parameter needed for
re-execution. Display strings and Swing component state are not configuration.

Manual work is reproducible only when it is named data. A hand-curated membership decision is
stored as a selection or decision set referenced by an operation; it is not hidden in the
current cards, sample, or group view.

### Identity

Names explain the plan to people. Stable declaration IDs connect inputs, operations, outputs,
and imports across renames. A transformation output also retains the provider-qualified source
identities and occurrence identities of its members, using the existing canonicalization
rules rather than inventing transformation-local identity.

## Shared transformation framework

The framework is UI-neutral and has four responsibilities:

```java
TransformationCompilation compile(
        TransformationModel model,
        TransformationInputCatalog inputs);

TransformationResult execute(
        TransformationCompilation compilation,
        TransformationInputs inputs,
        ProgressSink progress,
        Cancellation cancellation);
```

The exact Java types are deliberately unsettled. The required properties are:

1. Compilation is read-only and yields one immutable, explainable plan.
2. Execution consumes exactly that plan.
3. The executor imports neither Swing, ModelBuilder, nor TransformApp.
4. Input and output are provider-neutral typed object graphs with stable identities.
5. Every output reports its schema, members, lineage, counts, warnings, and failures.
6. Cancellation and progress use the existing process/batch reporting mechanisms.
7. Preview and full execution differ by an explicit bounded scope, not by implementation.

Existing transformation concepts should be factored rather than duplicated: `DomainModel`,
group/root bindings, filter groups, `TransformEngine`, canonicalization, selections, and the
ObjectView-facing schema adapters must converge on this compiled plan.

## Datasource adapter

After extraction, a transformation output is exposed as a datasource capability:

```text
provider: transformation-model
operation: output-class
parameters:
  transformation: HistoricalPositionsTransformations
  output: PositionWithHolders
```

The recipe does not repeat the filter, grouping, projection, or manual decisions. It resolves
the saved transformation model and names one output.

### The population contract must carry objects

The current class-population acquisition contract chiefly describes how to obtain identities:
a relation or explicit QID set is fetched from the external datasource afterwards. A
transformation output already contains complete typed objects, fields, and references.

Therefore the datasource boundary needs a derived/materialized population result alongside
the identity request:

```text
ClassPopulationResult
  IdentityPopulation(PopulationRequest)
  MaterializedPopulation(TypedObjectGraph, provenance, input signatures)
```

The names are provisional; the distinction is not. Converting a materialized graph back into
QIDs and fetching it again would discard transformed fields, duplicate work, and make the
external datasource—not the transformation—the accidental source of truth.

The generation planner merges a materialized result into the ordinary object pool. All later
construction, canonical-reference, quality, rendering, and snapshot paths remain shared.

## Dependency execution

Transformation models form a directed dependency graph with generation models, graph
constraints, curated decisions, populations, and saved outputs:

```text
generation output → transformation input → transformation output → generation input
```

For the worked case:

```text
Historical Positions generation
  produces Position

Historical Positions transformation
  consumes Position
  produces the initial PositionWithHolders instances

Historical Positions predecessor graph
  consumes the initial materialized PositionWithHolders class instances
  follows incoming P1366
  produces reviewed additions to PositionWithHolders

History generation
  consumes the expanded PositionWithHolders
  produces OfficeHolding and related classes
```

A build coordinator, not either desktop application, resolves this graph. It performs a
topological build and rejects cycles with the complete dependency path.

For each node it may:

1. reuse a compatible saved output;
2. run missing external acquisition/generation;
3. run the compiled transformation;
4. save the materialized output atomically; and
5. continue downstream.

An initial implementation may require an already saved upstream snapshot and stop when it is
missing. The target architecture, however, includes automatic upstream generation: a delivered
configuration is complete only when the coordinator can build its final servable output from
the declared external inputs and persisted curation data without opening ModelBuilder or
TransformApp.

## Project build as a domain-specific Makefile

The saved build is a Makefile-style artifact dependency graph. Its common presentation may be
an ordered operation list, but the stored meaning includes named inputs, outputs, prerequisites,
and signatures so one output may feed several consumers and several inputs may converge on one
output.

Model declarations are not operations. A class configuration, graph constraint, transformation,
or population declaration says *what*. An operation says *run, apply, publish, or save it*.
Conceptually:

```text
BuildOperation
  declarationId
  kind
  configurationReference
  inputs[]
  outputs[]
  executionSettings
  prerequisiteOperations[]
```

The first operation vocabulary should be small and explicit:

```text
GENERATE_PROJECT          configured class and statement generation
RUN_GRAPH_CONSTRAINT     read-only graph acquisition and classification
APPLY_GRAPH_DECISIONS    apply saved/default decisions to a named class population
RUN_TRANSFORMATION       execute one compiled transformation output
PUBLISH_POPULATION       publish an exact identity set from named class instances
SAVE_PROJECT_RESULT      atomically persist model, instances, annotations, and lineage
BUILD_DEPENDENCY         consume another owning project's compatible named output
```

Every elementary operation:

1. accepts immutable compiled configuration and explicit typed inputs;
2. runs without Swing, ModelBuilder, or TransformApp;
3. returns typed outputs plus counts, lineage, warnings, failures, and completeness;
4. reports through the shared process/progress/cancellation mechanism;
5. identifies every file it reads or writes through `DomainStorage`;
6. is deterministic apart from declared external datasource state; and
7. can be tested and invoked independently of the coordinator.

The coordinator is intentionally thin. It validates the graph, compares input/configuration
signatures with saved output signatures, topologically executes missing or stale operations,
reuses current outputs, and atomically publishes the requested target. It does not contain
generation, graph, transformation, or curation semantics.

### Build states

Each output has one visible state:

```text
MISSING
CURRENT
STALE
RUNNING
AWAITING_DECISION
FAILED
INCOMPLETE
```

Staleness is explained as a dependency path, not as a generic dirty flag. For example:

```text
History servable output is stale
  because PositionWithHoldersPopulation is stale
  because PositionsWithHolders used holderCount from Position snapshot abc…
  and the current Position snapshot is def…
```

### Curation without a UI dependency

Review is data, not a UI operation. `RUN_GRAPH_CONSTRAINT` produces immutable candidate
annotations. `APPLY_GRAPH_DECISIONS` consumes:

- saved per-candidate decisions;
- a configured default for newly accepted, rejected, and review candidates; and
- an explicit policy for unresolved decisions.

A fully unattended delivered build must choose a non-interactive policy or contain decisions
for every candidate. A configuration that requires manual review remains valid, but a headless
run stops in `AWAITING_DECISION`, writes no replacement final output, and names the exact graph
result requiring decisions. A desktop application may edit those decisions and resume the same
build; it does not perform a different kind of apply.

This preserves explicit curation without making a window part of execution. It also makes a
project's automation claim precise: “headlessly executable” means no operation can reach
`AWAITING_DECISION` under its saved policies and inputs.

### Historical Positions build

The worked build is:

```text
generate-position
  GENERATE_PROJECT
  output: Position instances

discover-position
  RUN_GRAPH_CONSTRAINT PositionDiscoveryConstraint
  requires: generate-position
  output: Position candidate annotations

apply-position
  APPLY_GRAPH_DECISIONS
  requires: discover-position
  output: curated Position instances

positions-with-holders
  RUN_TRANSFORMATION PositionsWithHolders
  requires: apply-position
  output: initial PositionWithHolders instances

discover-position-predecessors
  RUN_GRAPH_CONSTRAINT PositionPredecessorConstraint
  start: loaded class PositionWithHolders
  edge: incoming P1366 (replaced by)
  requires: positions-with-holders
  output: predecessor candidate annotations

apply-position-predecessors
  APPLY_GRAPH_DECISIONS
  requires: discover-position-predecessors
  output: expanded PositionWithHolders instances

publish-position-population
  PUBLISH_POPULATION PositionWithHoldersPopulation
  requires: apply-position-predecessors
  output: final exact population

save-historical-positions
  SAVE_PROJECT_RESULT
  requires: publish-position-population
  output: compatible Historical Positions model, snapshot, annotations, and build manifest

generate-history
  BUILD_DEPENDENCY save-historical-positions
  then GENERATE_PROJECT History
  output: final servable History domain
```

The ordered form is convenient for editing; the named requirements are authoritative. They
prevent the current manual ModelBuilder → TransformApp → ModelBuilder sequence from becoming
hidden application state.

## Persistence and compatibility

The owning project directory contains, conceptually:

```text
historicalpositions.model.json
historicalpositions.snapshot.json
historicalpositions.transformations.json
```

The exact filenames should follow `DomainStorage`; no UI or provider constructs paths locally.
One explicit Save model/domain operation states and writes every affected file.

Each materialized output records:

- transformation model signature;
- upstream model and snapshot signatures;
- input class declaration IDs;
- referenced selection/decision signatures;
- output class declaration ID and schema signature;
- execution time, counts, and completeness status.

The project result additionally contains a build manifest recording:

- build-configuration signature and requested target;
- every executed or reused operation and its input/output signatures;
- external datasource/cache identity where available;
- saved curation policy and decision-set signatures;
- exact produced filenames; and
- whether the requested output is complete and servable.

Compatibility is based on classified dependencies, not a single undifferentiated dirty bit.
Changing an unrelated vocabulary does not stale the output. Changing an input field used by a
filter does. The change-classification design tracked in issue #37 supplies the user-facing
consequences: keep, remap, enrich, rerun transformation, or regenerate upstream.

Writes are atomic at the project-result level. A new class declaration cannot become visible
without the snapshot containing its instances, and a failed transformation cannot replace the
last complete output.

Atomicity is per write, and says nothing about two writers. Both applications already write
`model.json` (#244), this design adds `*.transformations.json` to the files they share, and a
save loads the owning model, edits it and writes it back — so a session holding unsaved edits
in the other application loses them silently, with the last writer winning. Every write to a
file another application also owns states the signature it read and refuses when that signature
has moved, the way a recovery graph refuses a model whose fingerprint has changed. A refusal
that names the file and what changed is the minimum; merging is a later question.

## Import and ownership

Class configuration and instance publication cross different boundaries:

- an imported class declaration is read-only schema in the importer;
- the owner's class snapshot stays in the owning model and is never imported;
- the importer never reruns the imported class's original population producer;
- a referenced `PopulationSelection` is the explicit publication boundary and contributes
  exactly its saved identities, typed by the population's class;
- the class declaration follows transitively as the schema needed to type those members;
- unreferenced populations and imported classes contribute no instances; and
- several referenced populations of one class contribute their identity union.

This is already the bounded bridge used by ordinary generation: a referenced population's QIDs
enter the shared acquisition and materialization pipeline, so its members receive the imported
class schema without importing the owner's intermediate objects. The later transformation
datasource extends the same boundary with a `MaterializedPopulation` result when transformed
fields themselves—not only stable identities—must be published. It does not reintroduce class
snapshot import as a second path.

The UI action is therefore one concept: import/use a population. Showing the class configuration
that follows it is explanation of its type, not a separate instance-import operation.

## Application responsibilities

### TransformApp

- edits transformation models;
- previews a bounded execution through the shared executor;
- shows input/output counts and lineage;
- saves named transformation configuration and explicit curation data;
- runs and materializes an output when asked;
- never owns a private execution path.
- edits the same saved operation/configuration references used by headless builds.

### ModelBuilder

- references a published population whose class configuration follows as read-only schema;
- explains the upstream files, classes, transformations, and counts that will be used;
- invokes the shared build/execution services;
- never imports or regenerates the owner model's intermediate class snapshot;
- presents the same materialized instances through the normal multi-instance/ObjectView path.
- edits and explains project build operations, but does not own their executor.

Neither application launches or automates the other.

### Build coordinator

- loads and validates the saved project build graph;
- compiles every referenced declaration before starting expensive work;
- explains what will run, what will be reused, and every filename read or written;
- executes operations headlessly in dependency order;
- stops safely at an unresolved curation gate;
- resumes from compatible completed outputs; and
- publishes the final servable result only when all required outputs are complete.

## User-visible workflow

For authoring Historical Positions:

1. Generate and enrich `Position` in ModelBuilder.
2. Open the project in TransformApp.
3. Configure the holder-count/sitelink-count filter and output class
   `PositionWithHolders`.
4. Save `HistoricalPositionsTransformations`.
5. Run it: the confirmation names the input snapshot, transformation file, output class,
   output snapshot, and expected replacement behavior.
6. Configure `PositionPredecessorConstraint` from the loaded `PositionWithHolders` class through
   incoming `P1366`, save its application policy/decisions, and publish the expanded class as
   `PositionWithHoldersPopulation`.
7. Save the project build with final target `Historical Positions servable result`.
8. In History, reference the owner-controlled transformed class and declare the Historical
   Positions result as a build dependency.
9. Request `History servable result`. The coordinator builds or reuses Historical Positions,
   loads the expanded materialized position population, and then performs P39 discovery.

After authoring, a command-line entry point, service process, or **Build final result** action
performs the complete build with no UI interaction. These are adapters over the same build
coordinator, not separate pipelines.

## Failures must be direct

Examples:

```text
Cannot produce History.OfficeHolding.
HistoricalPositionsTransformations.PositionWithHolders requires
data/wikidata/historicalpositions/historicalpositions.snapshot.json,
but that snapshot does not exist. Generate and save Historical Positions.Position first.
```

```text
PositionWithHolders is stale.
Its saved transformation used Position snapshot signature abc…, while the current saved
Position snapshot is def…. Rerun HistoricalPositionsTransformations.
```

The system must not say only “run generation,” silently use all base instances, or hide the
failure inside a request list.

## Implementation order

1. **Define artifact and elementary-operation contracts.** Inventory existing generation,
   graph, transform, population, apply, and save entry points. Reuse their current owners;
   do not design parallel execution or filter/group models.
2. **Extract transformation compilation and execution.** Put a headless facade over the
   existing transform engine and force preview/full runs through it.
3. **Extract graph application and curation data.** Keep graph acquisition read-only; make
   decisions and population application a separate headless operation shared with the UI.
4. **Persist one transformation model.** Support filter-to-subclass first, including named
   selections and deterministic output schema.
5. **Add materialized class population mode.** Stop transformed subclasses inheriting base
   membership; fail directly when their output is unavailable. This step carries a data
   migration and cannot land without it: the moment inheritance stops being the fallback,
   the shipped History model is unrunnable, because `PositionWithHolders` has no materialized
   output and Historical Positions has no snapshot to produce one from (#273).
   `EveryShippedModelValidatesTest` holds that line and will fail until the shipped models and
   their snapshots are updated in the same change — which is the regenerate-or-migrate decision
   being made explicitly rather than discovered afterwards.
6. **Make population publication and project save operations.** They accept named typed inputs,
   use `DomainStorage`, report exact files, and write an atomic result plus build manifest.
7. **Add the datasource adapter.** Extend the population result to carry a typed object graph
   and merge it through the normal generation pool.
8. **Resolve imported outputs.** Load a same-owner materialized class from its project snapshot;
   do not copy or locally regenerate it.
9. **Add the Make-style build configuration and coordinator.** First force the complete
   Historical Positions → History build, then generalize the operation editor.
10. **Apply classified change consequences.** Explain precisely which upstream generation,
   transformation, graph result, or downstream snapshot is affected.
11. **Add headless delivery entry points.** A CLI/service invocation requests a named final
   target and produces the same files as the desktop action without loading UI classes.

## Forcing tests

- A transformed subclass with 357 saved members never expands to its base's 1,317 members.
- Its base fields remain available on every transformed instance.
- The saved transformation reruns headlessly with no Swing or TransformApp classes loaded.
- Every elementary build operation runs with no Swing, ModelBuilder, or TransformApp classes
  loaded and accepts only compiled configuration plus explicit inputs.
- Preview and full execution compile the same plan; only scope differs.
- A materialized output preserves transformed fields and references, not only QIDs.
- Importing a materialized class loads the owner's instances and leaves it read-only.
- Importing its `PopulationSelection` imports only the identity set.
- A missing/stale input names the exact model, class, transformation, and file and never falls
  back to inherited membership.
- An unrelated model-only edit does not stale the transformation output.
- A used filter field, operation parameter, selection, or upstream population change does.
- A failed/cancelled transformation leaves the previous complete output intact.
- A failed/cancelled build leaves the previous complete servable project result intact.
- A graph requiring unresolved decisions stops as `AWAITING_DECISION`; a graph with complete
  saved decisions or a non-interactive policy applies identically with and without a UI.
- A current operation is reused, while changing one used input reruns it and only its downstream
  dependants.
- A complete Historical Positions → History configuration builds the final servable History
  output from a headless entry point without loading desktop application classes.
- The build manifest names every operation executed/reused and every file produced.
- History discovers P39 subjects from exactly the final published transformed-plus-predecessor
  population, never from all 1,317 inherited `Position` members or only the 357 unexpanded seeds.
- Saving a working domain opened from a saved project writes that project's own files and
  leaves no same-name export under `data/wikidata/transform/` (#272).
- A write to a file the other application also owns refuses when the signature it read has
  moved, naming the file and what changed, rather than overwriting (#244).

## Non-goals

- Driving TransformApp windows from ModelBuilder.
- Embedding complete instance graphs in `model.json`.
- Treating every saved population selection as a transformed class.
- Re-fetching materialized objects from Wikidata merely because they have QIDs.
- Allowing the importer to mutate an owner model's transformation or instances.
- Automatically inferring and saving a transformation from the current visual grouping without
  an explicit Save transformation action.
- Making the coordinator reproduce business logic already owned by generation, graph, transform,
  population, or persistence components.
- Silently resolving a manual-review gate merely to make an unattended build finish.

## Done when

`PositionWithHolders` has one definition, one executable transformation producing its initial
members, and an explicit predecessor-graph stage producing reviewed additions, all owned by
Historical Positions. TransformApp and ModelBuilder edit and preview the same saved declarations;
neither owns a private execution path. A headless build request produces the complete compatible
Historical Positions result and then the final servable History output, recording exact lineage
and files, without user interaction when its saved curation policies are complete. No context can
reinterpret the class as all inherited `Position` members or silently bypass a review gate.
