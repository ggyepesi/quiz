# Constraining Discovered Graph Relations

## Status

First executable core, not yet user-configurable. The neutral evaluator can compare
direct values and nearest qualifying ancestors from a covered local graph. It extends
the candidate paths and curated frontier described in
[Configurable Knowledge-Graph Discovery](configurable-knowledge-graph-discovery.md);
it does not introduce a second graph-discovery mechanism.

## Problem

A graph path can discover a valid but irrelevant connection. In History, the existing
path connects two Positions through a Person who held both:

```text
Position A <- position held - Person - position held -> Position B
```

That relation is factually useful but semantically broad. A holder of Apostolic King
of Hungary may also have held Ban of Croatia; sharing a holder alone does not make the
two positions part of the same useful expansion.

The model needs to constrain a discovered candidate by facts about its two endpoints.
The constraint must not know about History, Positions, Wikidata QIDs or PIDs. Those
meanings belong to visible configuration and datasource bindings.

## Core distinction

Discovery and admission are separate operations:

```text
candidate path
    discovers A -> B

endpoint compatibility constraints
    accept, reject or defer A -> B
```

The candidate path explains why the nodes are connected. A constraint explains why
that connection belongs in this model. Rejecting a candidate does not deny the source
fact and does not remove either endpoint from acquired evidence.

## General form: compare values reached from both endpoints

The smallest general construct is a comparison between two bounded paths:

```text
Endpoint compatibility constraint
  left path:       A -> values
  right path:      B -> values
  comparison:      how the two value sets must relate
  missing policy:  reject or send to review
```

The first useful comparison is `INTERSECTS`: accept when the paths from A and B reach
at least one node with the same datasource identity.

### Shared jurisdiction

```text
left path:   A - jurisdiction -> jurisdiction values
right path:  B - jurisdiction -> jurisdiction values
comparison:  INTERSECTS
```

The property called `jurisdiction` and its provider relation are configuration. The
constraint engine sees only two labelled paths and two sets of entity references.

### Shared abstract ancestor

```text
left path:   A - hierarchy relation* -> qualifying ancestors
right path:  B - hierarchy relation* -> qualifying ancestors
comparison:  INTERSECTS
```

For the first History experiment:

```text
hierarchy relation:  subclass of
maximum depth:       configurable
qualifying ancestor: jurisdiction is absent
selection:           nearest qualifying ancestor on each branch
```

Here “abstract” is not a built-in node kind. It is the configured predicate
`jurisdiction is absent`. Another domain may use a different property or predicate.

Choosing the nearest qualifying ancestor matters. Comparing every ancestor eventually
admits unrelated endpoints through a very broad common node such as “political
position”. A depth bound remains mandatory even with the nearest-match rule.

## Candidate configuration vocabulary

The initial concept needs only:

- a bounded, directed path from each endpoint;
- an optional predicate selecting reached values;
- `INTERSECTS` as the comparison;
- `NEAREST_MATCHING` for hierarchical paths;
- `REJECT` or `REVIEW` when required evidence is missing;
- later, `ALL`, `ANY` and `NOT` for explicitly composing constraints when a real model
  forces more than one.

This can express, for example:

```text
shared abstract ancestor AND shared jurisdiction
```

or:

```text
shared abstract ancestor OR shared jurisdiction
```

More comparison operators should be added only when a concrete domain forces them.

## Datasource boundary

The graph layer owns paths, bounded traversal, set comparison and the decision result.
A datasource provider owns how configured relations and predicates are acquired. For
example, a Wikidata binding may map the hierarchy relation to `subclass of` and the
endpoint attribute to `jurisdiction`; another provider may use category-parent and
regional-classification edges.

Absence needs special care. In an open knowledge graph, “the property was not returned”
is not automatically proof that the property does not exist. A predicate such as
`jurisdiction is absent` is usable only when acquisition coverage says that property
was answered for the node. Otherwise the configured missing policy applies.

## Explanation and preview

The configuration UI should show the candidate path and its gate together:

```text
[A] <- position held - [Person] - position held -> [B]

Accept only when

[A] - subclass of, max 2 -> [nearest node without jurisdiction]
                              = same entity
[B] - subclass of, max 2 -> [nearest node without jurisdiction]
```

A preview should show accepted, rejected and unresolved examples with their reason:

```text
King of Bohemia
  accepted — common qualifying ancestor: King

Ban of Croatia
  rejected — qualifying ancestors King and Ban do not match

Unknown position
  review — no covered qualifying ancestor within depth 2
```

Discovery and preview are inspection only. Adding the constraint to the model remains
an explicit action.

## First-slice decisions

1. One endpoint constraint is enough. Composition waits for a configured case that
   cannot be expressed by one comparison.
2. Shared jurisdiction compares direct values. A hierarchy is already expressible as
   a separate bounded endpoint path if a domain later requires it.
3. `REVIEW` is necessary now: absence without complete adjacency coverage is unknown,
   not false.
4. The first predicate is `GraphRelationAbsent`. The name is deliberate: it asks a
   coverage-aware question about a directed graph relation. It is neither a numeric
   filter over field values nor a requirement that source metadata such as a label or
   sitelink exist.

   There are three superficially similar call sites, but merging them by syntax would
   erase important semantics:

   - a field-value filter compares already-bound values;
   - `requireLabel` and `requireSitelink` require source metadata;
   - a graph condition asks whether covered adjacency contains a directed relation.

   The declaration must nevertheless have one execution path for every place it is
   used. When frontier-side pruning is added, its provider adapter must compile the
   same `GraphRelationAbsent` declaration that local endpoint evaluation consumes; it
   must not add a second `lacks property` boolean to `FieldSourceMapping`.

   The measured frontier case still forces reuse between remote pruning and local
   evaluation: `lacks P1001` takes the `P279` closure under public office from 91,884
   nodes to 9,682, which is the abstract skeleton this note's
   nearest-qualifying-ancestor rule selects. Two details belong in that one graph
   predicate contract:

   - **Truthy or statement-level.** `wdt:` sees only best-ranked, non-deprecated
     statements and no *no value* snak, so an explicitly-no-jurisdiction office reads
     as `lacks` — which is right here, since asserting an office has no jurisdiction is
     the strongest evidence it is abstract. Measured, the choice moves 9 nodes of
     91,884, so it is safe to default to truthy and say so where it is configured.
   - **Absence needs coverage.** As the datasource-boundary section says, an unreturned
     property is not a property that does not exist. A predicate is only usable where
     acquisition coverage answered it for that node. This is the sharper of the two
     absence problems and nothing consults coverage today.

   Exclusions should be recorded rather than silently filtered: filter in the query so
   the wave stays cheap — a row limit spent on nodes that will be discarded truncates
   into the population being looked for — and record the count and the rule, so "the
   graph ends here" stays distinguishable from "82,202 nodes were excluded".

## Next implementation slice

Attach an optional endpoint constraint to the existing graph plan, compile its
relation demands through the datasource provider, and preview accepted, rejected and
unresolved candidate edges before any configuration is saved. The plan remains the
owner; the evaluator added here is execution machinery, not another persisted model.
