# Finding a Relevant Population

## Status

Design note, no code. Works out how to reach the members of a Wikidata population worth
serving: start from the marker the source states, then refine and extend it with a
declared gold set rather than a discovered heuristic. Written against Historical
Positions, where the configured population (`P31 = Q4164871`, ~92,300 members, largely
bulk-imported municipal offices) turned out to miss ~90% of what Wikidata explicitly marks
as a historical position.

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

## The stated key, found by asking

Q136649946 was the wrong marker to stop at. There is another, and it names this domain:

```text
Q114962596  historical position    1,314 instances
Q17279032   elective office        1,027 instances
```

A sample of `P31 = Q114962596`:

> khedive · janissary · Sapa Inca · Emperor of Austria · vizier · Holy Roman Emperor ·
> shah · khagan · Ban of Croatia · Emperor of China · monarch of Italy · German Emperor ·
> King of the Geats · Minister President of Prussia · king of Macedonia ·
> Governor-General of India · Reichsstatthalter · president of Germany

That is the domain, near enough as one would write it by hand. And it is almost disjoint
from what the domain currently generates:

```text
P31 Q114962596  ∩  P31 Q4164871   =   126  of 1,314
```

About 90% of the entities Wikidata explicitly marks as historical positions are absent
from the 92,300-member population, which meanwhile carries 39,158 French commune
mayoralties. The population was not too broad; it was the wrong population.

This is the third time the rule has paid here: **look for the stated marker before
deriving the distinction from shape.** Fan-out looked like it separated templates from
offices, and Q136649946 said it properly; Q136649946 then looked like the top of the
hierarchy, and Q114962596 is what actually names the domain. Each time the derived answer
agreed with the data long enough to be convincing.

Two honest limits. The marker is not clean — `janissary`, `Sipahi`, `zeybek` and
`Rashidun` are military or social roles and a dynasty, not offices — and the 40-row sample
above had no `ORDER BY`, so it is what WDQS returned first rather than a random draw: the
character is unmistakable, the proportions are not measured. Refining and extending that
1,314 is what the rest of this note is for.

## Why relevance is not discoverable from the data

Every property profile of this population describes what Wikidata contains, and what it
contains is mostly mayors. Relevance is a judgement about a quiz, not a fact about an
entity, and no amount of probing will produce it.

So the judgement is **declared**, in a gold set, and the data is asked only which
observable facts track it. That inverts the usual direction: the modeller supplies the
labels and the population supplies the contrast.

## The four steps

### 1. Start from the stated population, and extend it by traversal

Generate `P31 = Q114962596` and look at all 1,314. That is the population; `Position` is
not, and a class built on the marker starts 90% ahead of one built on Q4164871 and then
filtered.

The marker does not cover everything. `President of the United States` is not an instance
of it — it is a current office, and the domain presumably wants some of those. So the
traversal earns its place as the **extension** mechanism rather than the discovery one:
walk up from the stated population, see which generic classes it reaches, and use those to
propose offices the marker misses.

**That walk must follow `P31` and `P279` both.** Measured:

```text
President of the United States (Q11696)
    P279  president · head of state · head of government · commander-in-chief
Holy Roman Emperor (Q181765)
    P279  emperor
    P31   head of state · elective office · historical position · noble title
King of Spain (Q111670995)
    P279  Monarch of Spain · king regnant
```

The presidency reaches `head of state` (Q48352) by `P279`; the Holy Roman Emperor reaches
the same node by `P31`. A traversal following one relation misses the half that uses the
other. And `King of Spain` reaches no generic `king` in one hop — only the
country-specific `Monarch of Spain` — so the walk needs depth as well as both edges.

Rank what the walk reaches by sitelink count: it measures how widely a concept is known,
which is nearer to quiz relevance than any property profile, and it is existing
configuration — `FieldSourceMapping.RANK_BY_SITELINKS`, which `RuleNodeQueryBuilder`
compiles to `?value wikibase:sitelinks ?rankMeasure`.

**The ranking is read, not applied.** It proposes candidates; a modeller declares the ones
that survive. A threshold that runs is the fan-out mistake with a better number: it agrees
with the answer without being the fact, and drifts the moment a commune is tidied up.

### 2. Declare a gold set, built out of hard cases

A few tens of entities, named by hand, **as QIDs**. Labels cannot address them:
`"Holy Roman Emperor"@en` also matches a horse (Q5885972), and `"King of Spain"@en`
matches a single, a musical work and a disambiguation page alongside the title itself.

The worked starting set:

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
