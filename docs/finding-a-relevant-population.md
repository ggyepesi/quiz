# Finding a Relevant Population

## Status

Design note, no code. Works out how to narrow a large, flat Wikidata population to the
members worth serving, using a declared gold set rather than a discovered heuristic.
Written against Historical Positions, where `P31 = Q4164871` yields ~92,300 members of
which a large minority are bulk-imported municipal offices.

Companion to [[graph-relation-constraints]] (the traversal step 1 uses),
[[effective-class-explanation-and-sampling]] (what a sample may and may not establish)
and [[bounding-an-entity-end]] (where a surviving criterion is authored).

## The problem, measured

`Position` is `P31 = Q4164871`, no descendants: 92,300 members. It is flat and it mixes
kinds. One node, `mayor of a place in France` (Q382617), has 39,158 `P279` children, each
a single French commune's mayoralty.

Separating the template level out as `PositionType` (`P31 = Q136649946`, the stated
metaclass) was correct and is done — but it does not yield the offices a quiz would ask
about. Its 75 members are, by name:

```
mayor of a place in France / Italy / Poland / Sweden / Turkey / Tuscany / Wales /
the Czech Republic / the Philippines / the United States …          (30+ of 75)
head of state, head of government, prime minister, minister, governor,
member of parliament, ambassador, chairperson, official             (the rest)
```

No king, no monarch, no emperor, no pope. Q136649946 is used predominantly as bookkeeping
for imported municipal offices: it is the taxonomy **of the noise**, not of the tops.

The generic office classes are in neither population. Of 40 `P279` values reached from
`PositionType`, 21 are `Position` members and 19 are not — `politician`, `authority`,
`public office`, `president`, `prefect`, `legislator`, and `position` (Q4164871) itself,
the QID that *defines* the population. They sit above it, belonging to no stated
population at all.

## Why relevance is not discoverable from the data

Every property profile of this population describes what Wikidata contains, and what it
contains is mostly mayors. Relevance is a judgement about a quiz, not a fact about an
entity, and no amount of probing will produce it.

So the judgement is **declared**, in a gold set, and the data is asked only which
observable facts track it. That inverts the usual direction: the modeller supplies the
labels and the population supplies the contrast.

## The four steps

### 1. Propose the tops by traversal, ranked by how widely known they are

Walk `P279` upward from the `Position` population and classify what is reached: the
configured discovery graph, with the population as its start node, `P279` outgoing as its
edge, and the reached node intermediate. Rank the reached ancestors by sitelink count.

Sitelinks are the right signal here because they measure how widely a concept is known,
which is nearer to quiz relevance than any property profile, and because ranking by them
is existing configuration — `FieldSourceMapping.RANK_BY_SITELINKS`, which
`RuleNodeQueryBuilder` compiles to `?value wikibase:sitelinks ?rankMeasure`.

**The ranking is read, not applied.** It proposes candidates; a modeller declares the ones
that survive. A threshold that runs is the fan-out mistake with a better number: it agrees
with the answer without being the fact, and drifts the moment a commune is tidied up.

*Unverified, and it decides the edge:* whether `King of Spain` reaches `king` by `P279` at
all, or by `P31` to a class that is itself `P279` under `monarch`. One query settles it;
the graph configuration differs.

### 2. Declare a gold set, built out of hard cases

A few tens of entities, named by hand. The worked starting set:

```
Apostolic King of Hungary      historical, national, defunct polity
Holy Roman Emperor             historical, supranational, defunct polity
President of the United States modern, extant
```

Two of those three have no present-day `P17`, which is deliberate and load-bearing. With
twenty positives, one coincidence reads as a law, and the likeliest accident in a
hand-picked set is that every member belongs to a country that still exists — a criterion
that would silently exclude the Holy Roman Empire, the Caliphate and every office whose
polity is gone. For a domain whose subject is *historical* positions, that is the failure
that matters, and the only defence is to put the awkward cases in the set from the start.

The same argument applies at the other boundary. A set of twenty kings and presidents
teaches "head-of-state-ness", not relevance. The set should also carry famous *minor*
offices (Mayor of New York) and obscure *top* offices (the head of state of a micro-state
nobody would be asked about), because those are where the criterion has to decide, and a
criterion is only worth what it does at its boundary.

### 3. Profile the gold set and a matched random sample, the same way

Run the statement experiment over both: every property, every statement value and every
qualifier, per instance. The gold profile is a fold over data the experiment already
returns for an explicit QID list; the contrast profile is the same fold over a random
sample of the population.

Both halves are computed by one path on purpose. `DiscoverClassPropertiesQuery` already
reports per-property coverage and is tempting for the population half, but it samples from
a rule node or a `P31` override — it cannot take a QID list — and its aggregate SPARQL path
counts differently. Two coverages from two paths are not comparable, and one fact with two
discovery paths is a latent bug even while the numbers agree.

Sample size is bounded by patience, not by design: the statement query batches ten QIDs at
a time, so fifty instances is five batches and a thousand is a hundred batches of a heavy
query. Two small deliberate samples beat one large one.

### 4. Rank by lift, not by what the gold set shares

The intersection over the gold set is dominated by universals. `P31 = Q4164871` is true of
all twenty gold positions and all 92,300 others; so are most of the properties they share.

The quantity that carries information is the **ratio**:

```text
lift(p) = coverage(p, gold) / coverage(p, population sample)
```

A property at 90% in gold and 85% in the population says nothing. One at 60% in gold and
2% in the population is the criterion. Report both coverages beside every property and
qualifier pattern, and rank on the ratio — the contrast sample is what makes the number
computable at all, which is why step 3 profiles two sets rather than one.

Co-occurrence is worth ranking the same way: which properties travel *together* on one
instance. "Has an officeholder chain AND an inception date" can discriminate where neither
property does alone, and no aggregate per-property count can express it. With qualifiers,
this is what the statement experiment offers that property discovery cannot, and it is the
reason the experiment earns its place rather than duplicating what exists.

## What the output is

A ranked, evidence-carrying proposal: property or qualifier pattern, coverage in gold,
coverage in the population, lift. The modeller reads it and declares the surviving
criteria explicitly, as a membership or an entity-end bound.

It does not become a class, and it does not become a filter that runs. A union of fields
observed over a sample is a suggestion about a sample: its cardinalities and its value sets
are exactly what sampling cannot establish, which is why exhaustive controls are never
inferred from current data and why field count is asked rather than detected. Promoting an
observed profile to a generated class would rebuild that trap at class scale.

## The confound, stated

Officeholder chains, rich qualifiers and many sitelinks are all proxies for *well
documented*, which is a proxy for *notable*, which is most of what "relevant" means here.
The analysis will converge on notability whatever it is fed.

That is not a reason to skip it, but it does fix how to judge it: measure sitelink rank
first, as the baseline, and hold the statement profile to what it adds **beyond** that. If
lift over the gold set only recovers the sitelink ordering, the ordering is the answer and
the criteria are decoration.
