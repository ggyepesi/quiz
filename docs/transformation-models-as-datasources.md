# Transformation Models as Datasources

## Status

Partially implemented on 2026-10-27. Class-owned saved filter sources, materialized output classes,
headless run/publish operations, separate typed population artifacts, build manifests, and
typed imported-population consumption now exist. The checked-in Historical Positions model
declares that first executable workflow. Named dependency edges, fully classified staleness,
project-level atomic replacement, additional transformation operations, and the final
Historical Positions → History unattended delivery test remain work described by this document.

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
transformation source on that class**. It is not:

- an ordinary subclass whose population is regenerated from its base class;
- merely a `PopulationSelection` containing the output QIDs;
- a detached TransformApp-only result; or
- a second copy of transformation controls inside ModelBuilder.

TransformApp edits and previews a provider-neutral transformation configuration. A shared,
headless transformation executor runs it. ModelBuilder sees a named transformation output
through the same population-input boundary generation uses for imported selections. That
boundary injects the published typed roots directly and uses the ordinary compiled source plan
only for any additional non-materialized population identities.

The output class's `TransformationClassSource` remains the single source of truth for the
transformation. It is the population-source sibling of `GraphClassSource`,
`StatementClassSource`, and `AggregateClassSource`: it has no separate name, declaration ID,
or output-class reference. The containing class supplies all three.
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

> `PositionWithHolders` stores the transformation source that produces its population from
> these modeled input instances.

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

The checked-in implementation keeps the existing uniform boundary: a bound names a selection,
and a materialized class publishes one as a build output. The selection does not carry a second
producer reference. Its `className` resolves the one class-owned producer, while the build step
rewrites the exact published QIDs after materialization. The class says how instances are
produced; the selection names exactly the class instances its publication carries across the
import boundary — no subset of a larger artifact. An importer refuses a publication whose QIDs
are missing, extra or reordered against the saved selection, and the next build's publish step
rewrites the selection from the class's members; editing the selection by hand never changes the
producer and does not choose a subset.

## Class instance handling

Every class has one explicit population handling mode:

```text
GENERATED
    Its population is obtained from its configured datasource recipe, statement source,
    graph traversal, owned-production site, aggregate, or inherited generation rule.

MATERIALIZED_TRANSFORM
    Its population is produced by the saved TransformationClassSource on the class. Its base class
    contributes schema only. Population membership is never inherited as a fallback.
```

The complete instances do not belong inline in `model.json`. “The model owns the instances”
means that the class declaration contains its population producer and the companion snapshot
stores the materialized object graph beneath the owning project directory.

If a materialized output is missing or stale, execution fails with the output class, input
class, and exact missing/stale file. It must never silently generate the base population.

## Class-owned transformation source

A transformation source is immutable execution configuration stored on its output class.
Conceptually:

```text
GeneratedClassModel PositionWithHolders
  declarationId
  populationMode = MATERIALIZED_TRANSFORM
  baseClass
  transformationSource
    inputClassDeclarationId / inputClassName
    steps[]
      filter
      group
      project/derive field
      join/reference traversal
      include/exclude saved selection
      manual decision set
```

There is no project-level transformations list. One class has at most one population producer;
the validator requires the source exactly when `populationMode` is `MATERIALIZED_TRANSFORM`.
A build's `RUN_TRANSFORMATION` operation targets the output class declaration ID, and the
materialized files are keyed by that same ID, so renaming the class neither detaches the build
step nor orphans its artifact.

Operations are ordered and typed. Their serialized form owns every parameter needed for
re-execution. Display strings and Swing component state are not configuration.

Manual work is reproducible only when it is named data. Creating a subclass from a hand-picked
group creates a frozen `PopulationSelection` of the chosen QIDs and an `IN_POPULATION` subclass
condition referencing it. A type-spec group is refused until a typed headless step can preserve
its rule; it never silently becomes inherited base membership.

### Identity

Class names explain the plan to people. Stable class declaration IDs connect inputs, build
operations, artifacts, and imports across renames. A transformation output also retains the provider-qualified source
identities and occurrence identities of its members, using the existing canonicalization
rules rather than inventing transformation-local identity.

## Shared transformation framework

The framework is UI-neutral and has four responsibilities:

```java
TransformationCompilation compile(
        GeneratedClassModel outputClass,
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
provider: materialized-class
operation: output-class
parameters:
  classDeclarationId: PositionWithHolders declaration id
```

The recipe does not repeat the filter, grouping, projection, or manual decisions. It resolves
the class and reads that class's single saved population source.

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
  because the PositionWithHolders transformation used holderCount from Position snapshot abc…
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

**Implemented (2026-10-02).** The policy is the evidence condition's `ReviewDisposition`, stored
on the graph class: `AWAIT_DECISION` (the default), `INCLUDE_AND_REPORT` or `EXCLUDE_AND_REPORT`.
The walk continues through an entry awaiting a decision, so the reviewer sees everything it
reaches. `GraphApplication.apply` is the one apply operation: it returns `AwaitingDecision`
naming every undecided Review entry, or `Applied` with the new run and the result marked as
applied. The desktop refuses Apply with that message and keeps its results dialog open. Known
entry counts only while a chain from the start reaches it through entries that are not rejected
by hand or by evidence, so rejecting one takes what was reached only through it out of the result
(#312). Waiting is offered only on the output node: an intermediate node's entities are not in
the result, so it chooses Include or Exclude.

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
  RUN_TRANSFORMATION PositionWithHolders
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

acquire-position-predecessors
  GENERATE_PROJECT
  requires: apply-position-predecessors
  input: accepted additive graph identities as supplemental PositionWithHolders roots
  output: full modeled fields for the predecessor positions

restore-transformed-and-expanded-membership
  APPLY_GRAPH_DECISIONS PositionDiscoveryConstraint
  then RUN_TRANSFORMATION PositionWithHolders
  then APPLY_GRAPH_DECISIONS PositionPredecessorConstraint
  requires: acquire-position-predecessors
  output: filtered positions plus the accepted predecessor expansion

publish-position-population
  PUBLISH_POPULATION PositionWithHoldersPopulation
  requires: restore-transformed-and-expanded-membership
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

Generate replaces the whole run and carries forward only graph results that add members, so it
discards every narrowing applied before it; a transformation rebuilds its output class and so
discards graph memberships added to that class. That is why the restore step re-applies the
discovery constraint before the filter and the predecessor expansion after it. The coordinator
refuses, before running anything, a build in which a later step would read a membership such a
step discarded (`ProjectBuild.orderProblems`, held by `BuildOrderTest`).

The checked-in first implementation uses list order as the authority inside one project and an
explicit `BUILD_DEPENDENCY` across projects. The named requirements above are the next stored
form when the coordinator gains arbitrary same-project dependency edges. Even the ordered form
prevents the manual ModelBuilder → TransformApp → ModelBuilder sequence from becoming hidden
application state.

## Persistence and compatibility

The owning project directory contains, conceptually:

```text
historicalpositions.model.json
historicalpositions.snapshot.json
decldc7e9cd5ff6d4bc38bafd1bd87e0f18e.population.snapshot.json
decldc7e9cd5ff6d4bc38bafd1bd87e0f18e.population.json
declcdf5dab950474e99865ac7ada2512393.publication.snapshot.json
declcdf5dab950474e99865ac7ada2512393.publication.json
historicalpositions.build.json
```

Each file has exactly one producer. `RUN_TRANSFORMATION` writes the `*.population.*` pair: the
transformation's own output, exactly the members its steps kept, keyed by the normalized
`PositionWithHolders.declarationId` so a class rename leaves it attached. `PUBLISH_POPULATION`
stages the `*.publication.*` pair, keyed by the population selection's declaration id: the class's
members at publication, which include the memberships the predecessor expansion added after the
filter. It crosses the import boundary only when the following `SAVE_PROJECT_RESULT` writes it, so
a refused or failed save leaves the previous complete publication and saved selection together.
Both metadata files record the transformation signature and input snapshot signature they were
drawn from, so either goes stale when those change; neither stands in for the other. A population
owns a publication only while a publish step targets it; removing that step, or the selection,
makes Save remove the publication.

The build refuses, before anything runs, a publish step with no Save after it, and one with no
`RUN_TRANSFORMATION` of its class since the last Generate: the publication is labelled with the
class's current transformation, so its members must come from that transformation in this build,
not from a snapshot an earlier filter left behind.

The exact filenames should follow `DomainStorage`; no UI or provider constructs paths locally.
One explicit Save model/domain operation states and writes every affected file.

Each materialized output records:

- class-owned transformation-source signature;
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
`model.json` (#244), which now contains transformation sources on classes, and a save loads the
owning model, edits it and writes it back — so a session holding unsaved edits
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

An ordinary referenced population's QIDs enter the shared acquisition and materialization
pipeline, so its members receive the imported class schema without importing the owner's
intermediate objects. A population over a materialized class uses the same selection boundary;
the selection also owns one separate typed `*.publication.snapshot.json` artifact, staged by
`PUBLISH_POPULATION` and written by Save project. The importer loads only the selected members of that publication into the
shared object graph: transformed fields and
links survive, no population request is issued, and the owner's complete snapshot remains
private. This is not class-snapshot import under another name.

The UI action is therefore one concept: import/use a population. Showing the class configuration
that follows it is explanation of its type, not a separate instance-import operation.

## Application responsibilities

### TransformApp

- edits a materialized class's transformation source;
- previews a bounded execution through the shared executor;
- shows input/output counts and lineage;
- saves the class-owned transformation configuration and explicit curation data;
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
3. Configure the holder-count/sitelink-count filter group and create the subclass
   `PositionWithHolders` from it; the filter chain becomes that class's transformation source.
4. Save the model: the transformation is stored on the class in `historicalpositions.model.json`.
5. Run it: the confirmation names the input snapshot, output class, output population
   artifact, and expected replacement behavior.
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
Historical Positions.PositionWithHolders (saved transformation) requires
data/wikidata/historicalpositions/historicalpositions.snapshot.json,
but that snapshot does not exist. Generate and save Historical Positions.Position first.
```

```text
PositionWithHolders is stale.
Its saved transformation used Position snapshot signature abc…, while the current saved
Position snapshot is def…. Rerun the PositionWithHolders transformation.
```

The system must not say only “run generation,” silently use all base instances, or hide the
failure inside a request list.

## Implementation order and current state

1. **Implemented: define artifact and elementary-operation contracts.** Inventory existing generation,
   graph, transform, population, apply, and save entry points. Reuse their current owners;
   do not design parallel execution or filter/group models.
2. **Implemented for filters: extract transformation compilation and execution.** Put a headless facade over the
   existing transform engine and force preview/full runs through it.
3. **Implemented: extract graph application and curation data.** Keep graph acquisition read-only; make
   decisions and population application a separate headless operation shared with the UI.
4. **Implemented: persist one class-owned transformation source.** Support filter-to-subclass first, including named
   selections and deterministic output schema.
5. **Implemented: add materialized class population mode.** Transformed subclasses do not
   inherit base membership. Historical Positions now declares `PositionWithHolders` as the
   output of its holder-count filter; its next build creates the separate artifact.
6. **Implemented: make population publication and project save operations.** They accept named typed inputs,
   use `DomainStorage`, report exact files, and write an atomic result plus build manifest.
7. **Implemented at the generation boundary: add the typed population adapter.** Extend the population result to carry a typed object graph
   and merge it through the normal generation pool.
8. **Implemented: resolve imported outputs.** Load the referenced selection's separate typed
   population artifact; do not read the owner snapshot or locally regenerate it.
9. **Partially implemented: add the Make-style build configuration and coordinator.** The
   ordered operation list and coordinator exist; named dependency edges and the complete
   Historical Positions → History forcing build remain, then generalizing the operation
   editor.
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
- Consuming a published materialized population receives only that declared output as
  read-only typed objects; the owner's class snapshot and intermediate instances stay in the
  owning model.
- Importing an ordinary `PopulationSelection` imports only the identity set; importing a
  transformation-produced one brings exactly its selected members from that selection's publication.
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
