# Cross-Datasource Graph Discovery

## Purpose

Graph discovery should be able to learn from Wikidata, Wikipedia, DBpedia,
Wikisource and local structured sources without turning ModelBuilder into a generic
graph database or hiding cross-source identity decisions.

The reusable capability is deliberately narrow:

```text
configured frontier
        ↓
provider expands a labelled relation
        ↓
nodes + edges + evidence + provenance
        ↓
preview and coverage
        ↓
curator selects an expansion
        ↓
materialized domain graph
```

The core owns this workflow and its meaning. Each datasource provider owns its native
identifiers, configuration vocabulary, acquisition protocol and interpretation of a
completed adjacency request.

## Common expansion result

A provider returns a provider-neutral result equivalent to:

```text
DiscoveredGraph
  nodes                 stable source identity, labels and acquired properties
  edges                 source, relation, target and optional edge identity
  evidence              source document and supporting fragments
  provenance            provider, revision/digest and retrieval time
  coverage              completed, incomplete and unavailable inputs
  continuation          provider cursor or remaining bounded work, when applicable
```

This is an acquisition result, not a replacement snapshot format. Existing generated
classes and statement instances remain the domain representation consumed by
TransformApp and quiz generation.

## Provider responsibilities

A graph-capable datasource provider translates a configured recipe into two related
operations:

```text
preview(pattern, selected nodes)
    → bounded nodes and edges, expected work, provenance and current coverage

expand(pattern, selected nodes)
    → acquired nodes and edges, provenance, final coverage and unavailable inputs
```

The provider also declares:

- which native node and relation identifiers its editor accepts;
- supported directions and whether an edge has a stable identity;
- which properties can be demanded for reached nodes and edges;
- its pagination, batching and completeness semantics;
- how native results are materialized into configured classes and fields.

The generic layer must not name QIDs, PIDs, SPARQL, category titles or URL forms.

## Provider examples

### Wikidata

- Nodes: entities identified in the Wikidata namespace.
- Relations: properties and reified statements, including qualifiers.
- Acquisition: SPARQL for population/adjacency discovery and `wbgetentities` for
  precise entity facts.
- Strong use: qualified relations such as Person → OfficeHolding → Position.

### Wikipedia categories

- Nodes: articles and categories identified by wiki plus page identity.
- Relations: category membership and parent/subcategory adjacency.
- Acquisition: MediaWiki API with continuation.
- Strong use: proposing relevant populations and frontier concepts which can then be
  joined to structured entities.

Wikipedia categories are the preferred second implementation. The existing category
browser already proves bounded, human-directed traversal; adapting it will test the
contract against a source whose identities, relation grammar and completeness differ
from Wikidata.

### Wikipedia article links

Article links can propose adjacent people, places, works and events, but their
semantics are much weaker than category membership. They should initially be exposed
as evidence or ranked discovery suggestions, not automatically materialized edges.
Section identity and page revision must remain in the provenance.

### DBpedia

DBpedia can expose RDF predicates over Wikipedia-derived resources. It fits the graph
result naturally, but should follow Wikipedia categories: adding two RDF providers
does less to test the abstraction than adding the MediaWiki category graph.

### Wikisource and local data

Wikisource may contribute work, author, edition and translation relations. Local RDF
or tabular sources may participate when their configuration supplies stable node
identities, labelled relations and explicit correspondence rules. Neither requires a
new graph workflow.

## Cross-source correspondence

Native identifiers remain native. A Wikipedia page title is not a Wikidata QID, and a
DBpedia IRI is not made equal to either by normalizing its text.

Sources meet through explicit correspondence edges, for example:

```text
Wikidata QID
    —[sitelink, sourced by Wikidata]→ Wikipedia page
        —[category membership, sourced by Wikipedia]→ Wikipedia category
```

A correspondence carries provenance and, when inferred rather than asserted,
confidence and review state. Labels alone never establish identity. A provider may
suggest a correspondence; the model or curation state decides whether it is accepted.

## History example

Wikipedia can improve History discovery without replacing the authoritative
Wikidata statement model:

```text
Category:Kings of Hungary
        ↓ article membership
Wikipedia biography
        ↓ explicit sitelink correspondence
Wikidata Person
        ↓ P39 statement acquisition
OfficeHolding → Position
```

Here Wikipedia proposes people and records why they were proposed. Wikidata supplies
the structured office-holding statements and qualifiers. Differences are visible as
coverage or evidence disagreements rather than silently merged facts.

## Implementation sequence

1. Extract the existing Wikidata preview/expansion result behind a provider-neutral
   graph operation without changing its behaviour.
2. Keep the current Wikidata implementation as the first adapter and verify identical
   History results and coverage.
3. Adapt Wikipedia category browsing as a bounded preview operation.
4. Add category expansion with continuation, cancellation, provenance and durable
   coverage through the shared workflow.
5. Present source and evidence on previewed nodes, edges and frontier cards.
6. Add explicit Wikipedia–Wikidata correspondence edges using sitelinks.
7. Measure discovery yield: new candidates, accepted candidates, corroborated facts,
   unavailable inputs and acquisition cost per provider.
8. Add DBpedia, Wikisource or article links only when a concrete domain demonstrates
   information the first two providers cannot provide cleanly.

## Scope boundary

This direction is valuable while graph discovery remains a domain-building workflow:

- patterns are configured rather than arbitrary queries;
- expansions are bounded, previewable and cancellable;
- frontier choices are explicit and durable;
- every result retains source and quality information;
- generated domain classes remain the product;
- provider additions answer a demonstrated modelling need.

It has gone too far if ModelBuilder starts implementing a general graph query
language, generic graph storage, unrestricted connected-component crawling,
source-agnostic identity guessing, or a visual graph editor before concrete domains
require them. Those are mature products of their own and would obscure ModelBuilder's
distinctive value: turning heterogeneous evidence into a curated, explainable domain
model.

The forcing rule is therefore:

> Generalize only when a second real provider exposes a mismatch in the current
> contract, and keep the smallest abstraction that supports both providers without
> erasing their semantics.

