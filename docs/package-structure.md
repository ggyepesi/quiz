# Package ownership

The delivered concept is a **domain**. A quiz consumes a built domain; it does not own
configuration, acquisition, transformation, curation, discovery, persistence or the
workbenches that produce one.

## Top-level ownership

```text
quiz                         quiz engine, quiz modes and quiz-specific presentation
domain                       runtime domain/schema contracts
domain.runtime               runtime object graph and generated values
domain.config                saved class, field, source and rule declarations
domain.compile               compiled configuration and execution plans
domain.datasource            source contracts, adapters and discovery
domain.build                 headless generation and artifact production
domain.transform             headless transformations and grouping
domain.curation              corrections, merges and explicit decisions
domain.enrichment            source-backed proposals, review and application
domain.provenance            retained source identity and evidence
domain.persistence           project, snapshot and registry ownership
domain.workbench             shared workbench UI that is not owned by one operation
wikidata / wikipedia / ...   low-level protocol clients and parsers only
batch / process / work       shared execution infrastructure
```

Discovery is a datasource capability. Shared discovery contracts and UI live below
`domain.datasource.discovery`; provider implementations live below their provider, for
example `domain.datasource.wikipedia.discovery`. A discovery result changes nothing until
an explicit promotion writes domain configuration.

## Executable conventions

- A desktop application entry point lives in a dedicated `.app` package and its class ends
  in `App`: `domain.datasource.app.DataSourceApp`,
  `domain.datasource.discovery.app.DiscoveryApp`,
  `domain.transform.app.TransformApp`, and
  `domain.workbench.modelbuilder.app.ModelBuilderApp`.
- Reusable Swing code lives in `.ui`, never in `.app`.
- A command-line entry point lives in `.cli` and its class ends in `Main`.
- A demo lives in `.demo`; a benchmark lives in `.benchmark`.
- An `.app` class is a composition root and window-lifecycle owner. Reusable actions,
  state and panels remain in the operation package or `.ui`.
- Packages are lower-case singular concept names. An application name is not an ownership
  root merely because it was the first consumer of the code.
- Tests mirror the production package they test.

These rules govern product entry points. The older hand-written importers and inspection
programs under roots such as `flag`, `nobel`, `misc` and `aux` are legacy conversion tooling,
not examples to copy. They move under `domain.legacy` only when their ownership is examined;
they are not mechanically renamed into misleading product applications.

## Lower-level conventions

- Package by the fact or operation that owns the code, not by the first screen that used it.
  `domain.datasource.wikipedia.discovery` is a provider capability;
  `domain.workbench.modelbuilder.ui` is presentation of model configuration.
- Import domain-owned types and use their simple names in executable code. Do not spell a
  package-qualified `domain.*` type inside a method: local variables named `domain` and
  `datasource` then silently change how Java resolves it.
- A headless package exposes the smallest intentional public seam its UI needs. Do not keep
  UI in the provider package merely to reach package-private constructors or helpers.
- Persistent declarations live in `domain.config`; stored registries and artifacts live in
  `domain.persistence`; values present in the executable object graph live in
  `domain.runtime`. A class does not move between those owners according to which app opens it.
- Shared controls belong in `domain.workbench.ui`. An operation-specific control stays with
  its operation, for example `domain.enrichment.ui`.
- Low-level source clients (`wikidata`, `wikipedia`, and similar roots) expose protocols and
  parsed source data. They do not import domain workbench UI or quiz code.
- Package moves have no compatibility launchers or duplicate interfaces. All callers, tests,
  documentation, service declarations and IDE targets move in the same change.

## Dependency direction

```text
quiz -> domain.runtime

domain workbench UI -> domain operations
domain.build -> config + compile + datasource + runtime + persistence
domain.enrichment -> datasource + runtime + curation
domain.datasource adapters -> low-level source clients
```

`domain` never imports `quiz`. Low-level clients know neither domain workbench UI nor quiz.
Headless operations never import `.app` or `.ui`. Hand-written conversion inputs are inventoried
by `domain.legacy.LegacyDomains`; QuizFactory consumes only saved configured domains.

## Migration

Moves are made as complete ownership slices, without compatibility wrappers. Completed slices:

1. datasource contracts/providers, datasource discovery and enrichment;
2. provenance and curation;
3. transformations and their workbench;
4. domain configuration, compilation, build and ModelBuilder;
5. persistence/runtime consolidation and shared group/dialog ownership.

The read-only ModelBuilder HTTP surface lives with ModelBuilder under
`domain.workbench.modelbuilder.web`; `quiz.web` only hosts it alongside quiz delivery.

`wikidata.explore.query.logical` and `.result` remain temporarily because that package mixes
build execution, configuration discovery and source exploration. They must be split by those
owners rather than mechanically renamed to another catch-all package. `quiz.web` remains quiz
delivery; quiz-only graph, OCR and round code also remain under `quiz`.

Each slice updates package declarations, imports, tests, documentation, service files and
IDE entry points together, then passes a clean reactor build. A checked-in package guard
prevents new code entering a retired package while the remaining legacy allow-list shrinks.
