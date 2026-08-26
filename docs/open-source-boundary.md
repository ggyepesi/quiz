# Open-source boundary: evidence model builder

## Purpose

The first open-source part of this project should be the engine that turns a
declarative domain model and heterogeneous source data into an explainable,
reproducible typed snapshot.

Its working product statement is:

> Define a domain model, bind its classes and fields to data sources, inspect the
> acquisition plan, execute it safely, and trace how every resulting value and
> classification was produced.

This is useful without the quiz application. ModelBuilder is its authoring client;
TransformApp and quiz generation are downstream consumers of its snapshots.

“Evidence model builder” is a working description, not yet a repository or module
name. A public name should wait until the boundary has survived the History example.

## The boundary

```text
Domain definition + source recipes
                 |
                 v
        compile and validate
                 |
                 v
       immutable execution plan
                 |
                 v
  checkpointed source acquisition
                 |
                 v
      evidence / retained facts
                 |
                 v
       semantic convergence
                 |
                 v
 typed snapshot + quality + lineage
```

The compiled execution plan is the central seam. A durable `SourceRecipe` says
what the author configured. A `DatasourceProvider` resolves it into a prepared,
provider-owned operation. The execution plan validates conflicting targets and
records what will acquire data versus what an existing acquisition pass retains.

The present `datasource.api.SourceExecutionPlan` is the beginning of this seam,
not the finished public API. Today some prepared families still execute at their
existing Wikidata generation boundaries. Extraction is complete only when the
engine owns orchestration without duplicating those carefully tuned batching,
caching and checkpoint implementations.

## Inputs

- A domain definition: classes, inheritance, fields, cardinality, reference
  targets, statement and owned-component structure, and served roots.
- Source bindings attached to typed configuration slots such as class population,
  class identity/name, and field value.
- A registry of installed datasource providers.
- Run policy: cancellation, retry, concurrency, cache budget and checkpoint path.
- An optional prior snapshot for incremental work.

Provider-specific request grammar remains inside its provider. The common layer
asks for capabilities such as class population or field values; it must not grow a
lowest-common-denominator query language that attempts to reproduce SPARQL, MediaWiki
requests and future relational queries.

## Outputs

One generation result should carry all of the following together:

- the typed snapshot;
- its model fingerprint and source bindings;
- final generation quality, including unavailable identifiers and incomplete
  declarations;
- source-yield and acquisition measurements;
- evidence lineage and source document versions;
- an execution record suitable for the explanatory pipeline view;
- checkpoint/resume metadata.

Failure events are history; quality describes the final state. A request that failed,
split and later succeeded remains visible in the execution record but must not make
the resulting entity partial.

## Initial public concepts

The first headless API should expose concepts, not desktop widgets:

```java
CompilationResult compile(DomainDefinition definition,
                          DatasourceRegistry providers);

GenerationResult generate(CompilationResult compilation,
                          GenerationContext context,
                          ProgressSink progress);
```

The exact Java signatures are intentionally unsettled. The required properties are:

1. Compilation is read-only and produces an immutable plan.
2. Execution consumes the exact plan that was explained to the user.
3. Cancellation, progress, retries and checkpoints are explicit inputs or outputs.
4. Headless execution does not import Swing, ModelBuilder or TransformApp.
5. Providers own their request grammar and backend-specific optimization.
6. Snapshots do not require provider-specific Java object identities to deserialize.

Likely public capability areas are:

| Area | Responsibility |
| --- | --- |
| Model | Typed classes, fields, structures and source-binding targets |
| Datasource | Providers, recipes, prepared operations and capability discovery |
| Planning | Validation, demand closure and an immutable execution manifest |
| Acquisition | Batching, bounded concurrency, retry/split, cancellation and checkpointing |
| Evidence | Source references, document versions, fragments and value lineage |
| Construction | Reification, owned composition, classification and canonical references |
| Convergence | Bounded iterations until nothing is discovered, loaded, classified or composed |
| Quality | Final completeness, unavailable work and validation findings |
| Reporting | Source yield, cache/request measurements and phase execution history |
| Snapshot | Stable provider-neutral persistence and model fingerprinting |

## Included in the first extraction

- Datasource provider, registry, recipe, binding and execution-plan contracts.
- Class-population, class-name/identity and field-value bindings.
- Wikidata as the primary structured provider.
- Wikipedia categories and infoboxes as a structurally different secondary provider.
- The existing adaptive batch executor and append-only checkpoint semantics.
- Demand planning, retained-fact caching and source-yield measurement.
- Evidence records and structural media/value persistence.
- Semantic convergence, typed snapshot construction and generation quality.
- A headless runner and a machine-readable execution record.
- The model behind the explanatory pipeline diagram.

## Outside the first extraction

- Quiz assembly, question generation and play.
- TransformApp's complete curation workflow.
- Swing dialogs and `objectview` rendering as public dependencies.
- Domain-specific presentation, search and grouping configuration.
- Compatibility constructors and legacy model controls as public API.
- DBpedia unless the History example demonstrates unique value from it.
- A generic source-neutral query AST.
- A SQL provider until a concrete relational dataset can test the abstraction.

These can remain clients or later extensions. Excluding them is not a judgement on
their value; it keeps the first public promise small enough to explain and maintain.

## Dependency rule

The target dependency direction is:

```text
provider implementations
          |
          v
source-to-snapshot engine
          |
          v
snapshot/domain contracts
       /         \
ModelBuilder   TransformApp / quiz
```

In practical terms, code inside the extracted engine must not import packages owned
by ModelBuilder, TransformApp, quiz generation, Swing or `objectview`. A temporary
adapter may translate the existing `GeneratedProjectModel` into the headless domain
definition, but the public engine must not accept that UI-era model directly.

`DomainModel` currently mixes a useful domain contract with declarations referring
to `wikidata.explore.model`. That is evidence that the persistent domain definition
and the served/curatable snapshot view still need to be separated before extraction.

## Extraction sequence

### 1. Pin current behaviour

- Save execution records for Movies and Oscar Nominations as regression fixtures.
- Record root counts, final object counts, quality, source yield and representative
  lineage—not request order or wall-clock timing.
- Add the History example as an acceptance test before moving packages.

### 2. Introduce the headless facade

- Define compilation and generation results around the existing plan and pipeline.
- Run it from a test without constructing either desktop application.
- Make the explanatory component consume its execution record.

### 3. Move one dependency-closed vertical path

Move or factor contracts in this order:

1. datasource recipes, bindings, providers and prepared operations;
2. immutable planning and demand manifest;
3. acquisition/checkpoint contracts and evidence records;
4. construction, convergence, quality and snapshot output.

After each move, ModelBuilder must call the extracted path. Do not leave a parallel
“new engine” implementation beside the production generator.

### 4. Prove the boundary with History

History must be configured and generated only through public contracts. Any reach
into a ModelBuilder class is a missing public concept or an adapter responsibility.
The example should remain deliberately small enough to regenerate during development.

### 5. Publish only after the second provider is real

Wikidata plus Wikipedia should exercise different acquisition shapes and prevent a
Wikidata-shaped plugin API. At that point the package can become a separate module or
repository with semantic versioning and provider authoring documentation.

## Acceptance criteria for the first public slice

- A clean checkout can generate the History snapshot headlessly.
- The user can inspect the exact compiled plan before permitting network work.
- The explained plan and executed plan are the same immutable value.
- Interrupting and resuming produces the same final snapshot as an uninterrupted run.
- Every stored external value can report its provider and source document/version.
- The report distinguishes request history from final completeness.
- Adding a provider requires implementing datasource contracts, not editing the engine.
- Neither ModelBuilder nor TransformApp is needed on the engine's runtime classpath.
- Movies and Oscar Nominations retain their semantic output through the extraction.

## Open decisions

- Public project/module name.
- Whether the first stable snapshot format remains JSON or gains a versioned envelope.
- How much of the current rule-tree model belongs in the public domain definition.
- Whether identity/name is one source binding with two projections or two explicit slots.
- Which History facts warrant Wikipedia or Wikisource evidence strongly enough to make
  the value of multiple providers visible.

The History experiment should answer these questions before APIs are declared stable.
