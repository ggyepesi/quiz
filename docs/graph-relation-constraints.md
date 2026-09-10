# Constraining Discovered Graph Relations

## Status

Executable endpoint and node-evidence evaluators plus a standalone discovery
experiment, not yet provider-bound acquisition, persistence or ModelBuilder
configuration (issues #182, #183, #184). The neutral node evaluator follows alternative
direct evidence paths, applies alternative existence/equality tests, and retains its
three-valued verdict, Review disposition, witnesses and both-hop coverage. The endpoint
evaluator compares direct values and nearest qualifying ancestors from a covered local
graph. The existing shared-population demo now previews one frontier step at a time and
profiles its sample for possible constraints. It extends the candidate paths and curated frontier described in
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

Exhausting the depth bound without a qualifying ancestor is a **rejection**, not a
review. The two are different facts and the evaluator already separates them:

| what happened | outcome |
| --- | --- |
| the traversal completed within the bound and nothing qualified | **rejected** — the graph was read and gave an answer |
| adjacency was incomplete or unavailable, so the traversal could not complete | **review** — the graph could not be read |

Only the second is the coverage problem the datasource-boundary section describes.
Reporting a complete traversal as review would send every unrelated endpoint pair to a
person; reporting an incomplete one as rejected would let missing acquisition look like
a decided fact.

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

The hypothesis is that what these 28 share is not a level of government but a polity
that no longer exists. What Wikidata can be made to *establish* is narrower: reading
three properties any-of — `P1001` jurisdiction, `P17` country, `P2389` organization
directed by the office — and testing whether what they reach is dissolved accepts
**26 of the 28**. `P2389` alone recovers King of Sardinia and Corsica and Margrave of
Moravia, which name nothing through the other two. The remaining two, Count Palatine of
the Rhine and Head of the House of Habsburg, name no polity at all: the hypothesis may
still hold of them, and no available evidence decides it. That gap is the reason the
outcome below is three-valued rather than a boolean.

Applied to the whole population: **8,449 of 92,709** snapshot entities (9.1%), and
**15,388 of 145,708** live. Of 75,587 distinct polities the population reaches, 6,718
are dissolved. Domain-wide the sparse property carries most of the verdict —
jurisdiction 6,417, country 2,093, directs-organization 1,066 — the reverse of the
28-position sample, where country had the best coverage.

The positive counts are reliable because they came from positive witnesses. The old
classification also called 79,604 nodes rejected, but it treated every polity omitted
from a WDQS chunk response as “not dissolved”. That number therefore mixes genuine
contradiction with missing acquisition and is not a valid rejected count. Rejected and
Review must be measured again by the coverage-aware evaluator below.

### The construct

```text
evidence condition
  name       historical polity
  evidence   this -(P1001 | P17 | P2389)-> node      one or more labeled edges, any-of
  test       evidence -P576-> EXISTS                 an edge that exists
          or evidence -P31-> Q3024240                an edge reaching a constant
  outcome    satisfied -> accepted | contradicted -> rejected | undecidable -> review
```

A test is itself a bounded path plus a comparison, so the construct nests: what a
condition compares against may be another traversal rather than a constant. That is
the only recursion required.

### The reached node is Evidence, in the sense this design already defines

[Configurable Knowledge-Graph Discovery](configurable-knowledge-graph-discovery.md)
already names this expansion policy: **Evidence** — "use it for classification or
validation without adding a served domain object". A condition is the first configured
consumer of that policy, and it needs no new role.

The Kingdom of Hungary is reached under it. It is not a position, it never becomes a
served domain object, and no field of any instance holds it — but whether it is
dissolved decides whether Apostolic King of Hungary is acquired.

**The verdict alone is not enough to keep.** A stored boolean cannot be explained,
audited, reproduced or re-evaluated when the rule changes, and "8,449 accepted" without
the witness is the same defect as the exclusions this note already says must be
recorded rather than silently filtered. What acquisition retains for an evaluated node
is therefore the verdict *and* its evidence:

```text
node        Q6412254   Apostolic King of Hungary
verdict     accepted   by condition "historical polity"
witness     P1001 -> Q171150 (Kingdom of Hungary) + Q171150 -P576-> 1946
            P17   -> Q171150 (Kingdom of Hungary) + Q171150 -P576-> 1946
coverage    P1001, P17, P2389 answered for Q6412254;
            P576 and P31 answered for Q171150
```

A witness is a PAIR — the evidence edge that reached a node and the test edge that
satisfied it — because Apostolic King of Hungary reaches the Kingdom of Hungary through
both jurisdiction and country, and a flat list of test edges cannot say which relation
carried the verdict. That attribution is the per-relation measurement quoted above
(6,417 / 2,093 / 1,066, counted with overlap for exactly this reason), so evaluation
tests every reached evidence node rather than stopping at the first match: an early
exit decides the same outcome for less work and loses the attribution the record
exists to support.

An acceptance need not have a witness at all. An absence test matches by there being
no edge, so it accepts with an empty witness list and its coverage observation as the
record — acceptance is the test's verdict, never the presence of a witness.

This stays in the acquisition layer, beside the run's other provenance. It does not
become a served field, which is exactly what the Evidence policy means; and it is not
discarded, which is what makes a verdict defensible later.

The reusable cache stores the facts and their coverage, not a global “dissolved”
verdict. P576 and P31 adjacency are datasource facts; “dissolved” is the result of this
condition's particular alternatives and truthy/statement semantics. Another domain or
a changed condition may interpret the same facts differently. A derived-verdict cache
is justified only if later measurement forces it, and must then be keyed by the exact
condition and fact revision.

### Accept and reject are one construct with a polarity

`excludedTypeQids` is already the rejecting form, at one hop with an equality test: it
compiles to `PredicateObjectExclusion(P31, qid)` and is emitted as `FILTER NOT EXISTS`.
The predicate this note needs is expressible in the same backbone — verified live,
15,388 of 145,708:

```sparql
?value wdt:P31 wd:Q4164871 .
FILTER EXISTS {
  ?value (wdt:P1001|wdt:P17|wdt:P2389) ?polity .
  { ?polity wdt:P576 [] } UNION { ?polity wdt:P31 wd:Q3024240 }
}
```

**That query is an optimisation, not the acquisition shape.** It returns accepted nodes
only, so it cannot tell a node that named a present-day polity from one that named
none, and it discards both the 4,656 unknowns and the witness that justified every
acceptance. Used as the acquisition query it would contradict the section below before
it was implemented.

The acquisition contract is the classified result, not one large SPARQL query. Joining
145,708 members to three potentially multi-valued evidence relations and then to P576
and P31 would cross-product the rows and expose the membership result to a silent WDQS
partial response. The provider therefore follows the existing R17/R18 shape:

1. acquire the complete light membership backbone;
2. acquire P1001, P17 and P2389 adjacency in bounded batches for those members;
3. acquire P576 and P31 adjacency in bounded batches for the reached polity nodes;
4. evaluate locally, retaining the evidence edges and coverage used by each verdict.

The measured population reaches 75,587 distinct polity QIDs. At the existing
50-entity entity-document batch size, the second hop is about **1,512 batches** before
cache hits. This is a first-class acquisition phase, not a cheap predicate check. It
must go through the existing fact store, which reuses already fetched entity documents
and records exact adjacency coverage, so later conditions pay only for facts the store
does not already know.

A positive witness is sufficient for acceptance even if another alternative was
unavailable, because the condition is `ANY`. Rejection requires a non-empty evidence
set and complete test coverage for every reached evidence node. An empty evidence set
does not contradict the condition even when first-hop acquisition completed: it means
the configured evidence cannot decide, so the node goes to Review. Incomplete
first-hop or test coverage also goes to Review when no positive witness already decides
the result.

A provider may push the predicate down to the `FILTER EXISTS` form **only** when the
caller asked for accepted nodes alone — a preview count, say. The default must classify,
because a condition that cannot report what it excluded is not reviewable.

### The outcome is three-valued

A condition reports the same three outcomes the endpoint evaluator already reports —
**accepted**, **rejected**, **review** — and a rejected node is excluded from the
population, which is what the existing exclusions call blocking.

4,656 positions (5%) name no polity at all. Collapsing that into rejected loses Count
Palatine of the Rhine; collapsing it into accepted readmits the noise. It is the same
`REVIEW` requirement the first-slice decisions already state for absence, and for the
same reason: an unreturned property is not a property that does not exist, so review is
a visible set and not a rounding of a boolean.

The evaluator must distinguish an empty evidence set from contradicted evidence, and
both from acquisition that could not complete:

| what happened | outcome |
| --- | --- |
| at least one reached evidence node satisfies a test | **accepted** — a positive witness decides an `ANY` condition |
| no evidence node was reached, with or without complete first-hop coverage | **review** — the configured evidence says nothing about this node |
| evidence nodes were reached, every test relation was answered for all of them, and none satisfied a test | **rejected** — available evidence contradicts the condition |
| first-hop or test coverage was incomplete and no positive witness was found | **review** — acquisition cannot decide the condition |

Coverage is nevertheless retained in both Review cases: “the datasource contains no
configured evidence edge” and “the datasource could not answer the request” are
different explanations even though neither supplies a verdict.

Review also has an explicit, stored population disposition:

```text
On Review: include and report | exclude and report
Default:   include and report
```

The default preserves members when evidence cannot decide; it never turns missing data
into exclusion. Either choice keeps the Review members, reasons and coverage visible in
the run result. The modeller may explicitly choose `exclude and report` when a domain
prefers a narrow population, and a later evidence condition or hierarchy-based rule may
reduce Review before that disposition is applied.

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
- **The rescue is small.** At two-thirds the nine kinds recover 22 of 217 Review
  sampled members. Direct evidence decides thousands; propagation decides tens.

It is therefore a refinement to add after evidence conditions are in use, not with
them — and it is the only part of this with tunable numbers in it (a threshold, a floor
on the judged denominator, and nearest-not-any).

## Candidate configuration vocabulary

The initial concept needs only:

- a bounded, directed path from each endpoint, or from the single node being admitted;
- an optional predicate selecting reached values, including `EXISTS` on a further edge;
- `INTERSECTS` as the comparison;
- `NEAREST_MATCHING` for hierarchical paths;
- `ACCEPT`, `REJECT` and `REVIEW` as the three outcomes;
- `ANY` for alternative evidence relations and alternative tests.

Composition is in the first vocabulary rather than deferred, because the measured
condition needs it twice over: any-of across three evidence properties
(`P1001 | P17 | P2389`) and any-of across two tests (`P576` exists or
`P31 = Q3024240`). Deferring it would mean the selector that motivates this note
cannot be expressed by the construct the note proposes. General `ALL` and `NOT`
composition wait for a concrete condition that requires them. Accepting or rejecting a
condition is its outcome polarity and does not itself require a Boolean `NOT` operator.
Likewise, composing multiple endpoint constraints waits for a configured domain that
needs it. More operators should be added only when a concrete domain forces them.

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
  review — subclass adjacency became incomplete before a qualifying ancestor was found
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

## Implemented endpoint-evaluator slice

These decisions describe the already-built endpoint evaluator. They are its historical
scope line, not the scope of the node-admission slice below.

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
     absence problems. The local evaluators now consult coverage; provider acquisition
     must populate it rather than infer it from an empty result.

   Exclusions should be recorded rather than silently filtered: filter in the query so
   the wave stays cheap — a row limit spent on nodes that will be discarded truncates
   into the population being looked for — and record the count and the rule, so "the
   graph ends here" stays distinguishable from "82,202 nodes were excluded".

## Next implementation slice

**Node admission, not endpoint comparison.** The forced case is `Historical Positions`:
145,708 acquired entities where 15,388 are wanted, and no way to say which. No
configured domain yet needs two endpoints compared, so the endpoint evaluator — which
already exists as a core — waits for one.

The datasource-neutral vertical core now covers the common part of issues #182, #183
and #184 together: `GraphEvidenceCondition` declares direct alternative paths, `ANY`
existence/equality tests and Review disposition; `GraphEvidenceConditions` returns the
decision with evidence edges, test edges, positive witnesses and exact coverage.

The remaining slice is:

1. **#182** — compile the condition's two adjacency demands into the provider's batched
   fact acquisition.
2. **#183** — persist the Evidence policy and condition on the membership owner without
   adding a parallel field-source representation.
3. **#184** — retain per-run decisions in the acquisition result, report all three
   counts, and apply the configured Review disposition during population assembly.

The classified-result acquisition contract above governs all three: a run must be
able to say, for every entity it evaluated, which outcome it reached and on what
evidence. Pushing the predicate into a `FILTER EXISTS` is an optimisation available
only where the caller asked for accepted nodes alone.

Then attach an optional endpoint constraint to the existing graph plan, compile its
relation demands through the datasource provider, and preview accepted, rejected and
unresolved candidate edges before any configuration is saved. The plan remains the
owner; the evaluator is execution machinery, not another persisted model.
