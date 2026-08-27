# Graph Discovery as a Primary ModelBuilder Capability

## Status

**Product and architecture direction.** The first curated Wikidata pattern is working
in History, its configuration is explicit, and Explorer can preview the expansion on
bounded samples. This document states why the capability matters, what belongs in its
provider-neutral core, and how it should grow across datasources.

Implementation details and current limitations remain in:

- [Configurable Knowledge-Graph Discovery](configurable-knowledge-graph-discovery.md)
- [Curated Graph Frontier Expansion](graph-frontier-expansion.md)
- [Cross-Datasource Graph Discovery](cross-datasource-graph-discovery.md)

## Thesis

Configurable graph discovery can be one of ModelBuilder's highest-value and most
distinctive components.

Many tools can execute SPARQL, browse Wikidata, generate classes, or map records. The
less common capability is a complete, human-guided domain-growth loop:

```text
configure a semantic graph pattern
        ↓
preview its meaning on real examples
        ↓
acquire nodes and qualified edges
        ↓
materialize configured domain classes
        ↓
show expanded and frontier nodes
        ↓
let the user choose the next expansion
        ↓
persist the choice and continue incrementally
```

This places ModelBuilder between two unsatisfactory extremes:

- automatic traversal grows quickly into irrelevant connected regions;
- fully manual configuration misses useful adjacent concepts and populations.

A curated frontier lets the machine discover possibilities while the modeller decides
which possibilities belong to the domain. Every accepted expansion remains explicit,
repeatable and explainable.

## Proven example: History

```text
(Position:selected)
       ←[P39 / OfficeHolding]—
(Person)
       —[P39 / OfficeHolding]→
(Position:frontier)
```

Starting from selected Positions, ModelBuilder discovers their holders, retains all
P39 statements belonging to those Persons, materializes the statements as
`OfficeHolding`, and exposes newly reached Positions as a frontier.

The first run also demonstrated why the human boundary matters: an apparently related
position reached a Polish parliamentary population irrelevant to the intended
Hungarian-monarchy domain. A fixed recursion depth could not express that judgement;
the curated frontier could.

## Core concepts

### Graph pattern

A durable declaration of:

- source and target node classes;
- labelled, directed relations;
- edge/statement materialization;
- initial nodes or population rules;
- fields demanded for reached nodes and edges;
- expansion and stopping policies;
- quality and provenance requirements.

StatementClass is the first producer of such a pattern, not its permanent boundary.
Future patterns may follow ordinary fields, category membership, correspondence links,
or provider-native relations without requiring a reified statement class.

### Expansion policy

Each traversable step declares one policy:

- **None** — materialize normally; do not participate in discovery.
- **Curated frontier** — expose reached nodes and wait for user selection.
- **Terminal** — retain the reached node but never expand it.
- **Automatic** — expand to a bounded fixed point.
- **Evidence** — use the relation for classification or validation without serving it.

Only `None` and `Curated frontier` are implemented today.

### Coverage

Coverage belongs to a specific pattern, node, relation and direction:

```text
<pattern, node identity, relation, direction>
```

The durable states are encountered, queued, expanding, expanded and incomplete.
“Expanded” means the configured provider operation completed, including a valid empty
answer; it does not claim that an external knowledge base is objectively complete.

### Preview, execution and local traversal

The same pattern has three related presentations:

- **Explorer** previews a bounded sample and explains the traversal without writes.
- **ModelBuilder generation** acquires external data and advances durable coverage.
- **TransformApp** traverses the already materialized snapshot to select, group and
  curate instances; it does not silently expand the external graph.

For example, TransformApp may select every existing Person connected through
`OfficeHolding.position` to selected Positions. ModelBuilder performs external
acquisition only when the modeller chooses to expand those Positions.

## Datasource-neutral architecture

Graph discovery must support other datasources. The generic layer owns graph meaning;
a provider adapter owns how that meaning is acquired.

```text
Configured graph pattern
        ↓
Provider-neutral expansion plan
        ↓
Datasource provider operation
        ↓
Nodes + edges + evidence + provenance + coverage
        ↓
Domain materialization and snapshot
```

A provider implements a contract of the following shape:

```text
preview(pattern, selected nodes)
    → bounded nodes, edges, expected work and existing coverage

expand(pattern, selected nodes)
    → nodes, edges, provenance, completed/incomplete coverage
```

Provider recipes may use entirely different protocols while returning the same graph
concepts.

### Candidate providers

| Datasource | Example nodes and relations | Acquisition mechanism |
|---|---|---|
| Wikidata | entity, statement, property, qualifier | SPARQL plus `wbgetentities` |
| Wikipedia | article, category, parent/subcategory, article membership | MediaWiki API |
| DBpedia | resource and RDF predicate | SPARQL |
| Wikisource | author, work, edition, translation | MediaWiki/API/RDF where available |
| Local RDF | any configured RDF resources and predicates | local graph query |
| Text evidence | entity, document, extracted/corroborated claim | evidence providers and curation |

Wikipedia categories are the best second provider because the existing category
browser already performs explicit one-hop traversal. Generalizing that traversal into
the shared pattern contract will reveal which parts of the current abstraction are
truly generic and which merely reflect Wikidata.

## Identity and cross-datasource joins

Providers retain their native identities: Wikidata QIDs, Wikipedia page identities,
Wikisource titles/revisions, DBpedia IRIs, and so on. Graph discovery must not pretend
that these identifiers share a namespace.

Cross-datasource traversal uses explicit correspondence evidence:

```text
Wikidata entity
    —[sitelink / correspondence]→ Wikipedia article
        —[category membership]→ Wikipedia category
```

A correspondence is an edge with source, confidence and provenance, not an equality
assumption hidden in a string conversion.

## Position classes and vocabularies

An expandable entity class may be used as a value picker without becoming a
vocabulary.

- A **class population** is open: newly reached entities may legitimately join it,
  receive fields, be served, and have independently expanded neighbourhoods.
- A **vocabulary selection** is bounded: it primarily constrains admitted values, and
  values outside it are normally rejected rather than exposed as a frontier.

`Position` is therefore an open class population. `OscarCategories` is a bounded
vocabulary. Shared pickers should accept either source without erasing the semantic
difference.

## Role of Apache Jena

Apache Jena can strengthen the SPARQL implementation, but it is not the graph-discovery
architecture.

Use Jena incrementally for:

- parsing and validating generated SPARQL before execution;
- safe parameter and IRI binding;
- consistent prefixes and serialization;
- structured construction of increasingly rich graph-pattern queries;
- optional future evaluation over local RDF graphs.

Retain ModelBuilder's existing responsibilities:

- pattern configuration and explanation;
- provider selection and source bindings;
- Wikidata API acquisition;
- retries, batching, splitting and checkpoints;
- demand planning and fact retention;
- semantic convergence;
- provenance, quality and frontier coverage.

The recommended boundary is:

```text
Model and graph-pattern compiler
        ↓
SPARQL construction and Jena validation
        ↓
existing observable/retrying transport
```

A Jena-backed SPARQL compiler should begin as a validation boundary, then replace
small hand-built queries where it demonstrates clearer code. Rewriting the complete
query planner or snapshot representation is not a prerequisite.

## Development order

1. Validate the Explorer graphical preview against real History expansions.
2. Promote graph patterns from a StatementClass-only declaration to first-class model
   objects while preserving the current configuration as a migration source.
3. Add configured field-following steps such as
   `OfficeHolding.predecessor → Person` and `successor → Person`.
4. Implement the durable expansion ledger and emit queued, expanding and incomplete
   states from real execution outcomes.
5. Add local TransformApp traversal and named selections/groups over materialized
   patterns.
6. Implement Wikipedia category traversal as the second provider.
7. Introduce Jena as the SPARQL validation/compilation boundary when richer patterns
   begin to strain string construction.
8. Evaluate automatic bounded expansion only after curated coverage and cancellation
   are durable.

## Success criteria

Graph discovery succeeds when a modeller can:

- understand a configured traversal before running it;
- see exactly which data and provider operations it will use;
- distinguish selected, expanded, frontier and incomplete nodes;
- expand only semantically relevant neighbourhoods;
- resume interrupted acquisition without losing or duplicating work;
- reproduce the domain from persisted model decisions;
- traverse the resulting graph locally in TransformApp;
- combine providers through explicit, inspectable correspondence evidence.

The result is not merely a larger extracted dataset. It is a domain graph whose growth
is controlled, auditable and explainable.
