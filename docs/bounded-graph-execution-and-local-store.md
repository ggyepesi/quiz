# Bounded Graph Execution and Local RDF Storage

## Status

Design and staged implementation plan. This extends the existing curated graph
frontier from StatementClass-derived patterns to ordinary configured fields, then
executes those patterns through observable bounded waves over a local RDF graph.
GraphDB is an optional RDF4J-compatible backend, not a required application database.

Related documents:

- [Graph Discovery as a Primary ModelBuilder Capability](graph-discovery-product-direction.md)
- [Configurable Knowledge-Graph Discovery](configurable-knowledge-graph-discovery.md)
- [Cross-Datasource Graph Discovery](cross-datasource-graph-discovery.md)

## Objective

ModelBuilder should configure, explain and incrementally execute graph growth such as:

```text
Position seed <- P39 - Person
Person - P155 / P156 / P1365 / P1366 -> Person
Person - P39 -> Position frontier
```

The modeller chooses the semantic edges and frontier policy. Execution discovers the
graph in bounded, cancellable waves; retained local facts make repeated previews and
later waves fast. Missing adjacency is acquired from the owning datasource, committed
with provenance, and queried again locally.

## Two complementary abstractions

### Bounded waves are the control model

A wave has explicit inputs, demanded adjacency, limits and an outcome:

```text
input nodes
    -> query locally known adjacency
    -> identify missing adjacency
    -> acquire missing facts in retrying/checkpointed batches
    -> commit facts and provenance
    -> re-run the local step
    -> emit reached nodes, paths, coverage and frontier
```

The wave runner owns only graph semantics: depth, frontier transitions, stopping,
productivity/fixed-point decisions and the mapping between one wave's output and the
next wave's input.

It **composes the existing `batch` package** for execution. `BatchExecutor`,
`BatchPolicy`, `BatchProgress`, `BatchCheckpoint`, `BatchCheckpointStore`,
`ResultCommitter`, `FailureClassifier` and `WorkUnit` continue to own cancellation,
retry/split policy, checkpointing, incremental commit, progress and the separate
unavailable budget. A wave compiles its missing adjacency into `WorkUnit`s and consumes
the batch result; it must not contain another retry/checkpoint loop. Graph acquisition
is therefore a forcing consumer for finishing the existing batching convergence, not
a ninth batching mechanism.

### The local graph store is the execution index

The store owns indexed nodes and edges, local joins/property paths, graph-fact
deduplication, path/depth calculations, coverage queries and repeated previews.

It does not claim facts that have not been acquired. A local miss becomes a demand,
not an empty factual answer, unless coverage says that adjacency was completely
enumerated.

Here "coverage" means **acquired-adjacency knowledge** used while evaluating a wave.
It is intentionally not the saved product's graph-frontier coverage; the ownership
distinction is made explicit below.

## Configuration model

### Statement-derived patterns

The existing pattern remains the first composite traversal:

```text
selected Position
    -> holders through reverse P39
    -> materialized OfficeHolding statements
    -> newly encountered Position frontier
```

`GraphExpansionPattern` describes this materializing cycle and its coverage identity.

### Field-derived traversal steps

An ordinary typed entity field may explicitly opt into graph traversal:

```text
owner class + field path + target class
relation/provider + direction + expansion policy
```

Examples:

```text
Person.predecessor -> Person  (P155, outgoing, curated)
Person.successor   -> Person  (P156, outgoing, curated)
Person.replaces    -> Person  (P1365, outgoing, curated)
Person.replacedBy  -> Person  (P1366, outgoing, curated)
```

The field remains authoritative for property, target type and direction. A graph step
references that declaration rather than copying it into a second editor. Only typed
entity fields with a valid provider relation can enable traversal.

Relations remain distinct. A UI may present several as one expansion bundle, but the
store retains which relation produced every edge and path.

Initially, `NONE` loads normally without traversal and `CURATED` exposes newly reached
nodes for explicit expansion. Automatic recursion is deferred until durable
incomplete coverage and resource limits have been proven.

### Traversal does not imply membership

Following a typed edge answers which node can be used by the next traversal step. It
does not by itself say that the reached node becomes a served member of its target
class. Every step therefore needs an admission decision independent of expansion:

- **WAYPOINT** — retain identity, edge, path and evidence for traversal, but do not add
  a served class member;
- **MEMBER** — admit/stamp the reached node as a member of the configured target class;
- **EVIDENCE** — retain the relation only for classification/validation and do not
  traverse through the value.

The safe default for predecessor/successor/spouse traversal is `WAYPOINT`. A modeller
must explicitly choose `MEMBER` when the reached Persons should expand the served
population. This preserves the rule that "in the pool" and "has a reference to it" do
not imply membership; membership remains an explicit type/admission decision.

## Compiled plan

Configuration compiles into one provider-neutral plan:

```text
GraphExpansionPlan
  seeds
  statement-derived materializing steps
  field-derived traversal steps
  stopping and quality policy
  field demands
```

Neither the diagram nor execution independently infers graph semantics from Swing
controls. The plan drives the interactive configuration diagram, bounded Explore
preview, generation, frontier coverage and local TransformApp traversal.

`GraphExpansionPlan` is the target authority and **subsumes** today's
`GraphExpansionPattern`. During migration it may carry adapters for persisted
statement patterns, but diagram, preview, generation and coverage must move together
to plan/step identities. The transition is complete only when no production consumer
keys behavior directly from `GraphExpansionPattern`; the two are not permanent,
parallel configuration models.

## Local graph-store boundary

The core contract must not mention Wikidata, QIDs, PIDs, RDF4J or GraphDB:

```text
LocalGraphStore
  add(partition, facts)
  replace(partition, facts)
  query(graph query)
  adjacencyKnowledge(graph demand)
  close()
```

Conceptual types:

```text
GraphFact       node or labelled edge + evidence + provenance
GraphPartition  model, provider, run and lifecycle boundary
GraphDemand     required adjacency for nodes and relations
AdjacencyKnowledge unknown, complete, incomplete or unavailable source adjacency
GraphQuery      provider-neutral traversal request
GraphQueryResult nodes, edges, paths, depth and coverage
```

Implementations:

```text
InMemoryGraphStore          deterministic tests and first proof
Rdf4jMemoryGraphStore       zero-setup interactive use
Rdf4jNativeGraphStore       persistent embedded repository
Rdf4jRemoteGraphStore       GraphDB or another RDF4J server
```

GraphDB-specific APIs must not enter the core. GraphDB is selected as an RDF4J/SPARQL
repository through configuration.

## Fact ownership and partitions

The related representations have distinct responsibilities:

- `WikidataFactStore` — bounded, eviction-aware acquisition cache for one run.
- local graph — acquired source facts, provenance and **adjacency knowledge** used to
  decide whether a wave needs remote work.
- snapshot — materialized domain product served to TransformApp and quizzes; its
  persisted `graphDiscovery` block remains the authority for **frontier coverage**
  shown by the frontier workflow, cards and saved-product diagram.

These are different questions:

```text
local adjacency knowledge:
  "Did provider P completely enumerate relation R around node N?"

snapshot frontier coverage:
  "Did this saved product expand node N for configured plan step S?"
```

Wave execution may derive a proposed frontier transition from adjacency results, but
only snapshot construction commits product frontier coverage. The UI reads that one
persisted authority. A local repository must never silently advance the snapshot's
frontier state, and snapshot frontier state must never pretend source adjacency is
locally cached. The existing snapshot block survives until a future migration replaces
it atomically with a plan-keyed equivalent.

The graph is populated at successful acquisition/materialization boundaries, not by
inspecting `WikidataFactStore`: eviction means that cache intentionally never promises
to contain the complete run.

Recommended partitions separate model declarations, provider source facts,
cross-source correspondence evidence, materialized domain facts, and coverage with
acquisition provenance. Source claims and generated assertions must remain
distinguishable, and the repository must be rebuildable from retained evidence and
model decisions.

## History execution example

```text
Wave 0 — selected Position seeds
Wave 1 — incoming P39 produces holders
Wave 2 — configured predecessor/successor/replacement fields produce Person waypoints
Wave 3 — outgoing P39 produces the Position frontier
```

Every wave first uses locally covered facts. Only unknown adjacency is sent to remote
acquisition. When adjacency is fully covered, consecutive steps may be optimized into
one local SPARQL query without changing the wave semantics exposed to the user.

## Cross-datasource extension

Provider adapters translate native results into the same graph facts and coverage:
Wikidata statements, Wikipedia category/article relations, DBpedia predicates,
Wikisource work/author/edition relations and local RDF. Native identities remain
namespaced. Cross-source joins are explicit correspondence edges with evidence and
provenance, never hidden string equality.

## Package and module boundary

Incubate inside the current repository:

```text
datasource.graph.execution   plans, waves, limits and outcomes
datasource.graph.store       neutral facts, demand, coverage and store contract
datasource.graph.rdf4j       RDF4J implementations and RDF mapping
wikidata.graph               Wikidata demands, acquisition and fact mapping
```

Neutral packages must not import UI or provider classes. Once proven by Wikidata and
a second provider, extract them mechanically into a `graph-execution` Maven module in
this repository. A separate repository is premature until the API stabilizes.

## Implementation milestones

1. **Field traversal declaration** — persist policy on eligible entity fields,
   compile neutral traversal steps, and reject incomplete declarations.
2. **First-class compiled plan** — compose StatementClass and field steps and drive
   the shared configuration/Explore diagram.
3. **Bounded-wave core** — neutral wave inputs, limits, outcomes and fixed-point rule,
   with a deterministic in-memory store.
4. **History proof** — reproduce Position -> Person -> Position locally, add
   predecessor/successor, and report paths/depth.
5. **Conditional RDF4J storage** — embedded memory/native implementations, partitions
   and mapping, undertaken only when a forcing trigger below is demonstrated.
6. **Acquisition feedback** — distinguish unknown from complete-empty adjacency,
   acquire missing facts through existing batches/checkpoints, and resume the wave.
7. **Optional GraphDB backend** — configure an RDF4J repository and retain embedded
   zero-setup operation; only after RDF4J itself passed an adoption gate.
8. **Persistence and recovery** — model fingerprint, partition lifecycle,
   rebuild/resume diagnostics and quality reporting.

## RDF4J / GraphDB adoption gates

Milestones 3–4 may prove that the in-memory implementation is sufficient. RDF4J is
not adopted merely because it is next in the list. At least one measured forcing
condition must hold:

- the retained graph exceeds an explicit memory budget or causes unacceptable GC;
- useful adjacency/path state must survive application restart and rebuilding it from
  the snapshot is materially expensive or lossy;
- a required join, path, temporal or cross-datasource query is impractical or notably
  slower in the reference implementation;
- concurrent readers or dataset isolation require a repository boundary;
- measured repeated History/second-domain workloads justify indexed persistence.

GraphDB has a further gate: the RDF4J remote repository must demonstrate a concrete
scale, operational or query capability advantage over embedded RDF4J. If milestones
3–4 meet all success criteria without either gate, milestones 5–7 are deferred. That
outcome validates the abstraction while disproving the need for the heavier backend.

## Success criteria

The first proof succeeds when ModelBuilder can:

1. configure predecessor/successor traversal through ordinary fields;
2. show those steps in the interactive graph diagram;
3. reproduce holders and Position frontier from one History Position;
4. report the labelled path and depth of every reached node;
5. distinguish absent facts from completely enumerated empty adjacency;
6. acquire missing adjacency, add it, and resume without restarting;
7. repeat the preview with no remote request in the same session, while separately
   measuring whether cross-session reuse is worth persistent RDF storage;
8. produce the same frontier as the existing implementation.

This proves the architecture before committing to GraphDB deployment, automatic
closure or a general graph-query language.
