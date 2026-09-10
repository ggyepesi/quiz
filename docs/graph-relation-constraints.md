# Constraining Discovered Graph Relations

## Status

Executable core plus a standalone discovery experiment, not yet ModelBuilder
configuration. The node-admission form below is derived and measured but unbuilt
(issues #182, #183, #184). The neutral evaluator can compare direct values and nearest qualifying
ancestors from a covered local graph. The existing shared-population demo now previews
one frontier step at a time and profiles its sample for possible constraints. It extends
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

## General form: admit or block a node by evidence reached from it

The section above constrains a discovered *edge* by comparing its two endpoints. The
same machinery answers a different question — whether a *node* belongs in a population
at all — and the History work forced it before edge constraints were needed.

### The measured case

`Historical Positions` acquires `?position wdt:P31 wd:Q4164871`: 145,708 entities, of
which 39,158 are mayors of French communes. The 28 positions History actually holds are
Apostolic King of Hungary, Ban of Croatia, Holy Roman Emperor and the like. Two
selectors were tried against those 28:

| selector | keeps | why it fails |
| --- | --- | --- |
| jurisdiction (P1001) is a country | 18 of 28 | P1001 is missing on 10, including Emperor of Austria; absent on 61% of the domain |
| nearest P279 ancestor is a chosen kind | 25 of 28 | needs hand-picked anchors; P279 is absent on a fifth of the domain; `P279+` reaches *baryonic matter* in three steps |

What all 28 share is not a level of government but a polity that no longer exists.
Reading three properties any-of — `P1001` jurisdiction, `P17` country, `P2389`
organization directed by the office — and testing whether what they reach is dissolved
keeps **26 of 28**. `P2389` alone recovers King of Sardinia and Corsica and Margrave of
Moravia, which name nothing through the other two.

Applied to the whole population: **8,449 of 92,709** snapshot entities (9.1%), and
**15,388 of 145,708** live. Of 75,587 distinct polities the population reaches, 6,718
are dissolved. Domain-wide the sparse property carries most of the verdict —
jurisdiction 6,417, country 2,093, directs-organization 1,066 — the reverse of the
28-position sample, where country had the best coverage.

### The construct

```text
evidence condition
  name       historical polity
  evidence   this -(P1001 | P17 | P2389)-> node      one or more labeled edges, any-of
  test       evidence -P576-> EXISTS                 an edge that exists
          or evidence -P31-> Q3024240                an edge reaching a constant
  outcome    satisfied -> admit | contradicted -> block | unknown -> policy
```

A test is itself a bounded path plus a comparison, so the construct nests: what a
condition compares against may be another traversal rather than a constant. That is
the only recursion required.

### Evidence is a third role for a reached node

A node reached along a labeled edge can be a **value** kept on the instance (a field's
property), a **membership target** matched against (the membership triple's object), or
**evidence** — reached, tested, and discarded, with only the verdict surviving. The
Kingdom of Hungary is the third: it is not a position, never enters the domain, and
nothing about it is stored, yet whether it is dissolved decides whether Apostolic King
of Hungary is acquired. Naming that role is what makes this a general method instead of
three special cases.

### Admit and block are one construct with a polarity

`excludedTypeQids` is already the blocking form, at one hop with an equality test: it
compiles to `PredicateObjectExclusion(P31, qid)` and is emitted as `FILTER NOT EXISTS`.
The admitting form emits the same shape:

```sparql
?value wdt:P31 wd:Q4164871 .
FILTER EXISTS {
  ?value (wdt:P1001|wdt:P17|wdt:P2389) ?polity .
  { ?polity wdt:P576 [] } UNION { ?polity wdt:P31 wd:Q3024240 }
}
```

So this is an extension of the membership backbone rather than a second engine. What
the existing exclusion cannot express is the second hop, the existence test, and
any-of over alternative edges.

### The outcome is three-valued

4,656 positions (5%) name no polity at all. Collapsing that into `block` loses Count
Palatine of the Rhine; collapsing it into `admit` readmits the noise. It is the same
`REVIEW` requirement the first-slice decisions already state for absence, and for the
same reason: an unreturned property is not a property that does not exist, so an
unknown outcome is a visible set and not a boolean.

The presence form must compile through the same graph-predicate contract as
`GraphRelationAbsent`. One is coverage-aware absence and the other coverage-aware
presence; a second `has property` boolean on `FieldSourceMapping` would repeat the
mistake that section forbids.

### Deliberately not in this construct: propagating a verdict along the hierarchy

A kind can be judged by its members and lend that verdict to members with no evidence
of their own. Measured over 61 kinds and 2,190 sampled positions, scoring each kind by
the share of its judged members that name a dissolved polity separates cleanly:

```text
100%  Member of Parliament in the Parliament of England (40 judged)
100%  emperor · Ban · margrave · count palatine
 89%  king          88%  duke          76%  monarch
 39%  head of state 32%  prefect
  8%  member of parliament · minister
  0%  mayor · town mayor · village mayor · ambassador of a country
```

Any threshold from 60% to 75% selects the same nine kinds, so the number is not
delicate. Three findings matter more than the threshold:

- **`mayor` scores 0% of 40.** The feared failure — a few historical mayors admitting
  all 48,175 under `mayor` — does not occur.
- **Specificity carries the signal.** `member of parliament` is 8% while `Member of
  Parliament in the Parliament of England` is 100%, so only the NEAREST qualifying
  ancestor may be consulted. A rule reading any ancestor judges the England members by
  the general kind and drops them.
- **The rescue is small.** At two-thirds the nine kinds recover 22 of 217 silent
  sampled members. Direct evidence decides thousands; propagation decides tens.

It is therefore a refinement to add after evidence conditions are in use, not with
them — and it is the only part of this with tunable numbers in it (a threshold, a floor
on the judged denominator, and nearest-not-any).

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

## Controlled discovery demo

`FrontierConstraintDiscoveryFrame` is the demo entry point. It is separate from the
console closure engine, and each iteration is deliberately separated:

1. Select the incoming and outgoing relations from the same downloaded-property
   catalogue used by Explorer, or enter their PIDs.
2. Preview one bounded `source <- relation - bridge - relation -> candidate` step.
3. Inspect the candidate nodes and witness edges.
4. Profile only that bounded sample for candidate ancestors, candidate properties,
   bridge properties and qualifiers on the candidate statement.
5. Explicitly apply a selected constraint and preview the same frontier again.
6. Explicitly select admitted candidates as the next frontier.

Selection itself changes neither the frontier nor the active constraints. The demo is
a proving ground for which discovered constraints are semantically useful; it does not
persist another graph plan or wire experimental behavior into ModelBuilder.

## Using a downloaded hierarchy in TransformApp

TransformApp can classify an already-downloaded domain without extending its graph.
For a recursive reference field, **Nearest selected ancestor** creates a normal
persisted transform group whose rule contains that field and the explicitly selected
entity anchors. Each instance is placed under its closest reachable anchor. An
equal-distance match is placed in **Review** and a member reaching no selected anchor
is placed in **Unclassified**, so classification never silently removes instances.

The operation indexes the stored graph once and walks it in reverse from all selected
anchors. It performs no datasource query. This is deliberately separate from frontier
discovery: discovery decides which graph to acquire; the transform group gives the
downloaded graph a domain-meaningful classification such as King versus Writer.

The chooser offers each candidate with two sizes: how many nodes it reaches through
any number of steps, and how many name it directly. Both are needed, because an anchor
is chosen on what it would classify and neither number says that alone. In the
Wikidata position hierarchy `mayor` names 668 children and reaches 48 175, while
`mayor of a place in France` names and reaches the same 39 158 — so the reach ranks
the layer anyone would group by above the flat bucket, and the direct count says which
of the two a candidate is. The counts are discovery evidence: the group rule persists
the anchors, never the sizes.

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
