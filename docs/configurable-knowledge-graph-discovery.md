# Configurable Knowledge-Graph Discovery

For the product direction, cross-datasource architecture and development order, see
[Graph Discovery as a Primary ModelBuilder Capability](graph-discovery-product-direction.md).
The provider contract, correspondence rule and staged second-provider plan are in
[Cross-Datasource Graph Discovery](cross-datasource-graph-discovery.md).
Candidate paths that need shared-jurisdiction or common-ancestor admission are
described in
[Constraining Discovered Graph Relations](graph-relation-constraints.md).

## Status

**In scope for 0.1 — one pattern, one provider, one direction.** This is a deliberate
addition to the Rule Workbench demo release, made because curated expansion is the
first runtime knowledge-discovery pattern the engine has: nothing else lets a run
encounter something unconfigured, present it, and have the choice become part of the
model. The release checklist's own frame moves to 8–10 weeks to carry it, rather than
absorbing it silently.

**Shipped.**

- `4ab60f22` — discovery seeds separated from accepted statement values. A seeded
  target class finds subjects while leaving their other statements open; a vocabulary
  still does both jobs, by construction rather than by care.
- `749531ec` — the graph contract (`datasource.graph`), the Wikidata adapter deriving
  the pattern from statement models, coverage persisted with the snapshot, and the
  frontier workflow that turns a chosen node into an expansion (superseded by
  `110d5ee2`, which stopped that choice editing the authored model).
- Statement participation is now explicit: `Graph expansion = Curated frontier`
  enables the resolved pattern shown in the StatementClass editor; `None` preserves
  ordinary statement generation without a hidden frontier.
- `110d5ee2` — accepting a frontier node is execution history, not a model edit. The
  chosen nodes live in a durable ledger keyed the way coverage already is, applied to
  the disposable copy a run compiles from, saved with the snapshot and reset when a
  different model is loaded. `QUEUED` and `INCOMPLETE` are emitted: a queued node
  becomes expanded only when the run that expanded it completed. A pattern switched
  back to `None` goes dormant rather than losing its ledger.
- `fbbd9450`, `d107ac72` — an ordinary typed entity field can declare a graph edge
  without a StatementClass, one plan carries statement patterns and field steps
  together, and `GraphStaysNeutralTest` locks the neutral boundary. See
  [Bounded Graph Execution and Local RDF Storage](bounded-graph-execution-and-local-store.md).

Proven on History: `Person -[P39]-> Position` with one seeded position produced 140
office holdings across 24 positions, 23 of them encountered rather than configured.
A second round expanding Holy Roman Emperor took the domain to 491 objects across 42
positions, and after the prune fix (#119) preview and generation agree on every
frontier node.

**Deferred, and this list is the scope line.**

- A second provider. Wikipedia category traversal reuses the contract and is the test
  of whether the abstraction generalizes — after 0.1, because one provider proves the
  workflow and only the second proves the abstraction.
- `EXPANDING` remains declared and unemitted: nothing yet reports a run in flight, so
  a wave that dies mid-enumeration is only distinguishable afterwards, as `INCOMPLETE`.
- Acting on incomplete adjacency. `GraphWave` reports it on its own channel, but a
  truncated enumeration needs a continuation request that no runner issues yet.
- Bounded execution itself. `GraphWave` evaluates exactly the population it is handed;
  depth and resource limits belong to a plan runner that does not exist.
- Local RDF storage beyond the in-memory reference implementation.
- Restricting a frontier by target type (#120). A type restriction is a third case
  between an open frontier and a bounded vocabulary, and the derivation currently
  models two.
- Arbitrary graph-pattern languages, cross-datasource joins, automatic fixed-point
  traversal, graph editing and a visual pattern editor.

**Open, and on the release's critical path.** The pattern is proven on History, which
is [parked](roadmap-history-open-source.md). The demo needs it on Nobel, where the
consumer is academic lineage: 67 physics laureates have a doctoral advisor who is also
a laureate, which is the expansion an outsider should be asked to perform. Until that
exists, the frontier is demonstrated on a domain the release does not ship.

## Problem

ModelBuilder currently describes domain construction through several separate
concepts: population queries, field acquisition, statement reification, semantic
classification, category browsing and enrichment. Each is useful, but together they
implicitly perform a larger operation that the model cannot yet name or configure:

> Discover, materialize and incrementally grow a domain-specific knowledge graph from
> one or more external datasources.

Without this abstraction, traversal constraints and materialization filters become
conflated, graph growth is difficult to explain, and interactive discovery is added as
provider-specific UI rather than as a reusable workflow.

## Graph model

The discovered domain is a directed, labelled property multigraph.

- A **node** has a stable datasource identity, one or more domain classes, properties
  and provenance.
- An **edge** has an identity, label, direction, endpoints, properties/qualifiers and
  provenance.
- Multiple edges may connect the same endpoints. This is required for statements that
  differ by date, rank, source or other qualifiers.
- A qualified edge may be **reified** as a domain instance. A Wikidata P39 statement,
  for example, becomes an `OfficeHolding` so that start date, end date, predecessor and
  successor belong to the holding rather than to the Person or Position.

The graph is an acquisition and explanation layer. Existing generated classes and
instances remain the materialized domain view consumed by TransformApp and quizzes.

```text
External datasource graphs
          |
          v
Configured discovery and expansion
          |
          v
Acquired nodes, edges and provenance
          |
          v
Materialized domain instances
          |
          v
Snapshot, TransformApp and quiz generation
```

## Discovery configuration

A `KnowledgeGraphDiscovery` declares the following independent concerns.

### Seeds

The nodes or bounded population rules where discovery starts. Seeds initiate a
traversal; they do not necessarily define the complete accepted value domain.

### Node rules

Rules that admit reached resources as configured domain classes. Classification may
use datasource evidence without treating the chosen carrier class as an exclusive
claim about the real-world entity.

### Edge rules

Labelled datasource relations that may be acquired or traversed, including direction
and endpoint roles.

### Materialization rules

Rules deciding whether an edge remains a reference, supplies evidence only, or becomes
a reified statement/event instance.

### Expansion policy

The action taken when a node or edge becomes reachable:

- **Automatic** — traverse whenever its source node becomes eligible.
- **Curated** — expose the reached node as a frontier requiring user selection.
- **Terminal** — materialize it but do not traverse further.
- **Evidence** — use it for classification or validation without adding a served
  domain object.

### Field demands

The properties and metadata required for admitted nodes and reified edges. Demands
must be planned before acquisition where possible and retained according to the same
compiled plan.

### Stopping policy

Stop at an automatic fixed point, at a curated frontier, at configured resource
limits, or at a combination of these boundaries. Automatic recursion must always have
a safety bound even when idempotence should lead to a fixed point.

### Quality policy

Define what complete, partial, unavailable and rejected mean for each provider
operation. Historical request failures remain in the log; final graph quality reflects
the final resolved acquisition state.

## History forcing example

```text
(Position:selected)
       <-[P39 / OfficeHolding]-
(Person)
       -[P39 / OfficeHolding]->
(Position:frontier)
```

Configuration:

```text
Seed position: Q6412254 (Apostolic King of Hungary)
Reverse expansion: selected Position -> every Person holding it
Forward expansion: reached Person -> every P39 statement
Edge materialization: P39 statement -> OfficeHolding
Frontier: reached Positions not yet expanded in the reverse direction
```

This discovers the holders of `Q6412254`, then retains all their P39 statements. Thus
`Q253779` (Ban of Croatia) is admitted as a Position even though it was not an initial
seed. It remains partially discovered until the user chooses to enumerate all of its
holders.

Discovery values and materialization values must therefore be separate:

```text
discovery values:       [Q6412254]
accepted edge values:   unrestricted
```

An explicit Oscar-category vocabulary intentionally uses a different policy: the same
values both discover subjects and filter retained statements.

## Frontier and coverage

Expansion coverage is relative to:

```text
<pattern, node identity, edge label, direction>
```

A node can be expanded for incoming P39 while remaining unexplored for another edge.
The states are encountered, queued, expanding, expanded and incomplete. “Expanded”
means that the configured provider enumeration completed, including a valid empty
answer; it does not claim that the external knowledge base itself is objectively
complete.

The model owns pattern definitions, seeds and selected future expansions. The snapshot
owns acquired graph data and completed/incomplete coverage. The append-only batch
journal owns transient request execution and restart state. These must not be
collapsed into one notion of completion.

## Datasource-neutral provider contract

The generic graph layer must not name QIDs, PIDs, SPARQL, Wikipedia categories or any
other provider vocabulary. A provider translates its recipe into adjacency work:

```text
preview(pattern, selected nodes)
    -> expected work and already-covered inputs

expand(pattern, selected nodes)
    -> nodes, edges, provenance, coverage and unavailable inputs
```

Examples include:

- Wikidata statements and qualifiers;
- Wikipedia parent/subcategory/article-membership edges;
- DBpedia RDF predicates;
- Wikisource work, author, edition and reference relations.

Cross-datasource correspondence joins nodes by explicit evidence. It does not require
all participating classes to share a provider or identifier scheme.

## Existing mechanisms to factor together

- `PopulationSubjectLoader` already performs bounded reverse PID traversal.
- `QualifierLoader` already acquires and reifies complete Wikidata statements.
- `SemanticConvergence` already processes newly reachable semantic work to a fixed
  point.
- The Wikipedia category browser already provides human-directed one-hop traversal.
- `LoadedDeclaration` already distinguishes “not requested” from a completed empty
  result for exact node identities.
- `BatchCheckpoint` already supplies append-only, resumable request execution.
- `SourceBinding`, datasource operations and `SourceExecutionPlan` already separate
  durable recipes from installed capabilities.
- The shared process workflow already supplies preview, execution, cancellation,
  result tabs and apply semantics.

The discovery abstraction should connect these mechanisms rather than create a second
pipeline, graph store or process runner.

## Implementation sequence

1. Separate discovery constraints from accepted statement-value filters.
2. Express the History P39 round using that split while preserving existing domains.
3. Introduce explicit graph-pattern and expansion-policy model types.
4. **Implemented:** compute and display newly encountered frontier nodes after
   generation, alongside the nodes whose reverse adjacency was expanded.
5. **Implemented:** persist node-relative expansion coverage in the snapshot.
6. **Implemented for ModelBuilder:** preview/select/expand/results/apply using the
   shared workflow and its virtualized card rendering. Applying selected frontier
   nodes opens the ordinary Generate-domain preview for the next expansion round.
7. Reuse the contract for Wikipedia category traversal as the second provider.
8. Generalize only where the second implementation demonstrates a real mismatch.

Arbitrary graph-query languages, automatic full connected-component traversal,
cross-source query optimization, graph editing and a visual pattern editor are outside
the first version.

## Architectural consequence

This abstraction is a candidate boundary for an open-source component: a configurable,
provider-extensible knowledge-graph discovery engine with explicit acquisition
coverage and curated frontier expansion. ModelBuilder is its configuration and
explanation environment; TransformApp is its review and curation environment.
