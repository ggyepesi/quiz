# Curated Graph Frontier Expansion

## Status

**In scope for 0.1; the first slice is shipped** (`749531ec`). Generation computes the
frontier, the workflow shows encountered nodes beside expanded ones, and choosing a
node writes its identity into the target class's seeds and reopens Generate — so the
graph grows through the choice rather than through a depth setting.

Participation is explicit on the StatementClass: its **Graph discovery / Expansion
policy** must be `Curated frontier`. Statement structure supplies the resolved
source/relation/edge/target pattern, but structure alone never silently enables graph
traversal.

The Explorer's **Graph patterns** tab provides the read-only explanation before a
full generation: choose an enabled pattern and up to a few expansion-node QIDs, then
preview the reverse subjects, reified statement edges and newly reached frontier as a
diagram plus searchable card tabs. Previewing never changes seeds or the snapshot.

What that slice does *not* do, deliberately: no durable execution ledger (coverage is
computed at save, so `QUEUED`, `EXPANDING` and `INCOMPLETE` are declared and
unemitted); no second provider; no persisted selection state of its own; no automatic
recursion. The scope line lives in
[Configurable Knowledge-Graph Discovery](configurable-knowledge-graph-discovery.md).

The first real run is the argument for the design. Among the 23 positions History
reached was "member of the Sejm of the Polish People's Republic", held — according to
Wikidata — by Franz Joseph I, who died in 1916. Automatic traversal would have
enumerated its holders and pulled Polish parliamentarians into a Hungarian monarchy
domain. A curated frontier shows it to a person, who declines. That case was not
constructed for the document; it was waiting in the first expansion.

This is the first interactive specialization of
[Configurable Knowledge-Graph Discovery](configurable-knowledge-graph-discovery.md).
The broader design owns graph discovery, provider operations and materialization;
this note focuses on human-controlled traversal of the resulting frontier.

## Purpose

Model datasource acquisition as traversal of a directed, labelled property
multigraph. Let generation discover one neighbourhood, show the newly encountered
frontier, and let the user explicitly choose which frontier nodes to expand next.

This provides controlled recursion without an opaque depth setting or an accidental
full connected-component crawl.

## Graph model

- **Node** — stable datasource identity, configured class, properties and provenance.
- **Edge** — identity, label, direction, endpoints, qualifiers/properties and
  provenance.
- **Reified edge** — an edge with its own domain instance. For History, a Wikidata
  P39 statement is materialized as `OfficeHolding` because dates, predecessor,
  successor, rank and provenance belong to the statement rather than either endpoint.
- **Expansion pattern** — the configured node roles, labelled edge, traversal
  directions, seeds and materialization rule.

The first concrete pattern is:

```text
(Position:selected)
       <-[P39 / OfficeHolding]-
(Person)
       -[P39 / OfficeHolding]->
(Position:frontier)
```

Starting with `Q6412254` (Apostolic King of Hungary), generation discovers every
Person holding that position, loads all P39 statements of those Persons, reifies the
statements as `OfficeHolding`, and exposes every other reached Position—such as
`Q253779` (Ban of Croatia)—as frontier.

## Expansion state

Coverage is relative to a pattern, node, edge label and direction:

```text
<pattern, node identity, edge label, direction>
```

States are:

- **Encountered** — reached through another expansion but not enumerated in this
  direction.
- **Queued** — selected for the next expansion.
- **Expanding** — acquisition is running.
- **Expanded** — the provider completed the requested adjacency enumeration, even if
  it returned no edges.
- **Incomplete** — expansion was requested but acquisition did not complete.

“Expanded” describes completed acquisition against a datasource response; it does not
claim that the external datasource itself is objectively complete.

## Ownership and persistence

- The **model** owns the expansion-pattern definition, initial seeds and the user's
  selected expansion nodes.
- The **snapshot** owns encountered/expanded/incomplete coverage and the resulting
  nodes, edges and provenance.
- The **batch journal** owns transient pending/split/completed request work needed to
  resume an interrupted expansion.

Domain coverage and batch completion must remain separate: a completed HTTP batch is
not itself proof that a graph neighbourhood was completely materialized and saved.

## Datasource contract

A provider implements adjacency expansion without exposing QIDs, PIDs, SPARQL or
MediaWiki concepts to the generic graph layer:

```text
preview(pattern, selected nodes) -> expected work
expand(pattern, selected nodes)  -> nodes, edges, provenance, coverage
```

Provider-specific recipes translate a configured edge into concrete operations. For
example, Wikidata P39 may use SPARQL for reverse subject discovery and
`wbgetentities` for complete statements, ranks, qualifiers and calendar-aware dates.
Wikipedia categories can implement the same contract with parent, subcategory and
article-membership edges.

## Existing mechanisms to reuse

- `PopulationSubjectLoader` already traverses a PID backwards from bounded values.
- `QualifierLoader` already loads and reifies complete statements for reached
  subjects.
- `SemanticConvergence` already runs idempotent newly-reachable work to a fixed point.
- The Wikipedia category browser already performs user-directed one-hop traversal.
- `LoadedDeclaration` already records exact per-node acquisition coverage, including
  valid empty answers.
- `BatchCheckpoint` already provides an append-only resumable execution frontier.
- Datasource bindings and `SourceExecutionPlan` already separate durable recipes from
  installed provider operations.

The new feature should factor and connect these mechanisms rather than introduce a
parallel graph engine.

## First implementation slice

1. Split statement **discovery values** from **accepted materialization values**.
2. Preserve current behaviour for explicit vocabularies such as Oscar categories:
   their values both discover subjects and filter retained statements.
3. Treat a seeded target class on a source-less statement as discovery-only. In
   History, `Position.seedQids = [Q6412254]` discovers Persons, while all P39 statements
   of those Persons are retained.
4. Verify editable and compiled-model parity, validation and runtime filtering.
5. Expose the newly reached Positions as a computed frontier.

## Following milestones

1. Add a durable expansion ledger derived from final acquisition coverage.
2. Add the shared preview/select/execute/results/apply workflow using
   `VirtualizedCardList` for frontier nodes.
3. Persist selected expansions in the model and completed coverage in the snapshot.
4. Reuse the contract for Wikipedia category traversal; generalize only where that
   second provider exposes a real mismatch.

Automatic fixed-point traversal, arbitrary graph-pattern languages, cross-datasource
joins and a visual graph editor are intentionally outside the first version.
