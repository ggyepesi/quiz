# Transformation Models as Datasources

## Status

Design decision, not yet implemented. This document defines how a class produced in
TransformApp becomes a reproducible, model-owned input to ModelBuilder without automating
one desktop application from the other.

The worked case is:

```text
Historical Positions.Position              1,317 generated instances
        ↓ saved TransformApp configuration
Historical Positions.PositionWithHolders     357 materialized instances
        ↓ imported into History
History.OfficeHolding                      P39 statements into those 357 positions
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

The transformation configuration remains the single source of truth. The datasource recipe
contains only a stable reference to one of its outputs.

## Why the current subclass representation is insufficient

TransformApp currently persists a semantic subclass by naming its base and clearing its
independent population. That preserves the output schema but loses how its instances were
obtained. A later generator therefore reads:

```text
PositionWithHolders extends Position
no independent population source
```

and reasonably—but incorrectly—uses all inherited `Position` members. Historical Positions
still shows 357 `PositionWithHolders` instances because its snapshot retained the transformed
objects. History imports only the declaration and consequently generates 1,317.

The lost fact is not “these 357 QIDs form a selection.” The lost fact is:

> `PositionWithHolders` is the output class of this transformation over these modeled input
> instances.

A population selection remains useful as an exact reusable identity set for graph starts and
statement bounds. It does not replace a transformed class carrying schema, fields, references,
lineage, and complete typed instances.

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

Transformation models form a directed dependency graph with generation models:

```text
generation output → transformation input → transformation output → generation input
```

For the worked case:

```text
Historical Positions generation
  produces Position

Historical Positions transformations
  consumes Position
  produces PositionWithHolders

History generation
  consumes PositionWithHolders
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
missing. Automatic upstream generation is a later orchestration step, not a reason to blur the
transformation contract.

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

Compatibility is based on classified dependencies, not a single undifferentiated dirty bit.
Changing an unrelated vocabulary does not stale the output. Changing an input field used by a
filter does. The change-classification design tracked in issue #37 supplies the user-facing
consequences: keep, remap, enrich, rerun transformation, or regenerate upstream.

Writes are atomic at the project-result level. A new class declaration cannot become visible
without the snapshot containing its instances, and a failed transformation cannot replace the
last complete output.

## Import and ownership

An imported materialized class remains owned by its transformation model:

- its declaration is read-only in the importer;
- its instances are resolved from the owner's compatible materialized output;
- the importer neither regenerates them nor edits them;
- its base relation supplies schema, never fallback membership;
- missing owner output is an explicit blocking error.

Importing only a `PopulationSelection` remains a different valid operation. It imports an exact
identity set, not the class's schema or complete transformed objects. The UI must name these
actions separately: **Import class** and **Import selection** are not substitutes.

## Application responsibilities

### TransformApp

- edits transformation models;
- previews a bounded execution through the shared executor;
- shows input/output counts and lineage;
- saves named transformation configuration and explicit curation data;
- runs and materializes an output when asked;
- never owns a private execution path.

### ModelBuilder

- declares a class population recipe pointing to a transformation output;
- explains the upstream files, classes, transformations, and counts that will be used;
- invokes the shared build/execution services;
- imports owner-controlled transformed classes read-only;
- presents the same materialized instances through the normal multi-instance/ObjectView path.

Neither application launches or automates the other.

## User-visible workflow

For Historical Positions:

1. Generate and enrich `Position` in ModelBuilder.
2. Open the project in TransformApp.
3. Configure the holder-count/sitelink-count filter and output class
   `PositionWithHolders`.
4. Save `HistoricalPositionsTransformations`.
5. Run it: the confirmation names the input snapshot, transformation file, output class,
   output snapshot, and expected replacement behavior.
6. Save Historical Positions.
7. In History, import `PositionWithHolders` or reference it as a transformation datasource.
8. Generate History. The run reports that 357 materialized positions were loaded from the
   owning model before P39 subject discovery begins.

A later **Build dependencies and generate** action may perform steps 1, 5, and 8 in dependency
order. It is orchestration over the same operations, not a new pipeline.

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

1. **Describe the existing transform state.** Inventory every TransformApp operation and
   identify which already has a serializable owner. Do not design a second filter/group model.
2. **Extract compilation and execution.** Put a headless facade over the existing transform
   engine and force preview/full runs through it.
3. **Persist one transformation model.** Support filter-to-subclass first, including named
   selections and deterministic output schema.
4. **Add materialized class population mode.** Stop transformed subclasses inheriting base
   membership; fail directly when their output is unavailable.
5. **Add the datasource adapter.** Extend the population result to carry a typed object graph
   and merge it through the normal generation pool.
6. **Resolve imported outputs.** Load a same-owner materialized class from its project snapshot;
   do not copy or locally regenerate it.
7. **Add dependency orchestration.** Reuse compatible artifacts, then build stale/missing nodes
   in topological order.
8. **Apply classified change consequences.** Explain precisely which upstream generation,
   transformation, graph result, or downstream snapshot is affected.

## Forcing tests

- A transformed subclass with 357 saved members never expands to its base's 1,317 members.
- Its base fields remain available on every transformed instance.
- The saved transformation reruns headlessly with no Swing or TransformApp classes loaded.
- Preview and full execution compile the same plan; only scope differs.
- A materialized output preserves transformed fields and references, not only QIDs.
- Importing a materialized class loads the owner's instances and leaves it read-only.
- Importing its `PopulationSelection` imports only the identity set.
- A missing/stale input names the exact model, class, transformation, and file and never falls
  back to inherited membership.
- An unrelated model-only edit does not stale the transformation output.
- A used filter field, operation parameter, selection, or upstream population change does.
- A failed/cancelled transformation leaves the previous complete output intact.
- History discovers P39 subjects from exactly the 357 transformed positions.

## Non-goals

- Driving TransformApp windows from ModelBuilder.
- Embedding complete instance graphs in `model.json`.
- Treating every saved population selection as a transformed class.
- Re-fetching materialized objects from Wikidata merely because they have QIDs.
- Allowing the importer to mutate an owner model's transformation or instances.
- Automatically inferring and saving a transformation from the current visual grouping without
  an explicit Save transformation action.

## Done when

`PositionWithHolders` has one definition, one executable transformation, and one 357-instance
materialized result owned by Historical Positions. TransformApp previews and edits it;
ModelBuilder can invoke it through the datasource plan; History consumes those same instances;
and no context can reinterpret the class as all 1,317 inherited `Position` members.
