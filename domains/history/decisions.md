# History domain decision log

This log records decisions forced by the History demonstration. It is intentionally
separate from the generated model: the model says what is configured; this file says
why, which alternatives were considered, and what observation would make us revisit it.

## Decision template

```text
## HNNN — Short title

Status: proposed | accepted | rejected | superseded
Date:

Scenario
The concrete History instance, field or workflow that forced the question.

Observed evidence
What Explore or a measured run showed. Include representative identifiers and counts.

Options considered
- Reuse a native source API or open-source project.
- Adapt an existing project capability.
- Add a provider-neutral capability.
- Defer or omit the feature.

Decision
What we will do now, including its deliberately excluded scope.

Generalization test
Which existing domain or second source proves this is not a History special case.

Revisit when
The measurement or new scenario that would invalidate the decision.
```

## H001 — Bound the first History experiment

Status: accepted
Date: 2026-08-25

### Scenario

“History” is too broad to be a useful first configuration or a fast development
fixture. We need enough structural variety to exercise statements, roles, places,
documents and multiple sources without turning the first run into a catalogue of
European history.

### Observed evidence

The Revolutions of 1848 already provide:

- a parent event, [European Revolutions of 1848 (Q3588)](https://www.wikidata.org/wiki/Q3588);
- several regional events related by `part of`, including
  [the Hungarian Revolution (Q473716)](https://www.wikidata.org/wiki/Q473716),
  [the German revolutions (Q3699)](https://www.wikidata.org/wiki/Q3699), and
  [the French Revolution of 1848 (Q622774)](https://www.wikidata.org/wiki/Q622774);
- people with ordinary biographical fields and event/office relationships, including
  [Lajos Kossuth (Q157040)](https://www.wikidata.org/wiki/Q157040),
  [Sándor Petőfi (Q81219)](https://www.wikidata.org/wiki/Q81219),
  [Lajos Batthyány (Q702016)](https://www.wikidata.org/wiki/Q702016), and
  [Artúr Görgey (Q716001)](https://www.wikidata.org/wiki/Q716001);
- a historical document-like work,
  [the Twelve Points (Q385152)](https://www.wikidata.org/wiki/Q385152), with author
  and publication date.

The event records are heterogeneous: the parent is a revolutionary wave, while
regional events are typed as revolution, conflict, rebellion or war of national
liberation. This is useful evidence against defining the population as one `instance
of` value before Explore.

### Options considered

- All nineteenth-century European history: rejected as unbounded.
- Only the Hungarian Revolution: coherent, but weak as a test of heterogeneous event
  classification and cross-regional relationships.
- A small graph rooted in the Revolutions of 1848: selected.

### Decision

Start with a bounded graph containing 3–5 events, 10–20 people, 3–5 places and 2–5
documents. The first vertical slice may be even smaller. Population rules are not yet
decided; the QIDs below are exploration seeds, not a hand-maintained final population.

### Generalization test

The resulting constructs must continue to describe Oscar statement roles and Movies
entity kinds without domain-specific branches.

### Revisit when

The sample cannot demonstrate a qualified relationship or a meaningful secondary-source
fact, or regeneration becomes too slow for routine development.

## H002 — Begin population discovery from explicit seeds

Status: proposed
Date: 2026-08-25

### Scenario

The first Explore session needs representative entities, but the observed event types
do not justify one global class-membership query.

### Initial seeds

| Candidate role | Entity | QID | Why inspect it |
| --- | --- | --- | --- |
| Parent event | European Revolutions of 1848 | Q3588 | Parent/part structure, dates, location |
| Regional event | Hungarian Revolution of 1848 | Q473716 | Participants, dates, country, Wikipedia coverage |
| Regional event | German revolutions of 1848 | Q3699 | Different type shape, `part of` relation |
| Regional event | French Revolution of 1848 | Q622774 | Different regional event shape |
| Regional event | Sicilian revolution of 1848 | Q2708273 | Rebellion typing and historical-country qualifier |
| Person | Lajos Kossuth | Q157040 | Office history and extensive source coverage |
| Person | Sándor Petőfi | Q81219 | Explicit conflict participation, structured name |
| Person | Lajos Batthyány | Q702016 | Qualified positions with predecessor/successor |
| Person | Artúr Görgey | Q716001 | Military participant and structured name |
| Document | Twelve Points | Q385152 | Author, publication date, public-domain work |

### Decision to make after Explore

Compare at least these population shapes:

- regional events reached through `part of Q3588`;
- people reached from event participant/conflict properties;
- office holders reached through qualified `position held` statements;
- explicit seed selections for entities that have no reliable connecting property;
- documents reached from event or author relationships.

Prefer source-declared relationships over a curated QID list only where their coverage
and precision are visible. A small explicit selection remains legitimate for an example
fixture; it must not masquerade as a discovered complete population.

### Generalization test

The selected class-population recipes must use the existing population operation and
selection constructs. If a missing operation is observed, its first cross-domain test is
an equivalent population in Movies or Oscar Nominations.

### Revisit when

Explore supplies coverage counts and counterexamples for each candidate population.

## Pending decisions exposed by the first session

- H003: event membership—`part of`, subclass/type evidence, explicit selection, or a
  composition of these.
- H004: participation—ordinary event field, reverse person field, or reified statement.
- H005: office holding as the first qualified relationship.
- H006: historical document boundary and whether the Twelve Points behaves as an entity,
  work or document-evidence source.
- H007: first Wikipedia contribution with measurable unique yield.
- H008: whether any candidate document has a usable Wikisource edition/correspondence.

## H009 — Resolve content-category members in MediaWiki

Status: accepted
Date: 2026-08-25

### Scenario

Loading Wikipedia's `Category:Revolutions of 1848` showed rows such as “German
revolutions of 1848–1849 — 274048”: the Wikidata column was blank and only the
MediaWiki page ID was present.

### Observed evidence

The category reader fetched page titles and IDs from MediaWiki, then attempted to
resolve their QIDs through a second WDQS sitelink query. That join can miss redirected
or normalized titles and recently changed sitelinks. MediaWiki exposes the page's
`wikibase_item` directly through `pageprops`.

### Decision

For namespace-0 content categories, use `categorymembers` as an Action API generator
and request `pageprops.wikibase_item`. Title, page ID and QID now arrive in the same
paginated response. An article genuinely lacking a Wikidata item remains visible with
a blank QID. WikiProject assessment categories contain Talk pages and retain their
separate article-title resolution path for now.

### Generalization test

The implementation remains the ordinary Category tool and is covered with generic
category/continuation fixtures; it contains no History category or entity names.

### Revisit when

The general MediaWiki acquisition milestone absorbs content categories and WikiProject
assessment categories into one page-demand implementation.

## H010 — Browse category structure lazily

Status: accepted
Date: 2026-08-25

### Scenario

A flat load of `Category:Revolutions of 1848` could not show whether regional events
were direct articles, organized into subcategories, or discoverable only through a
broader parent. Selecting a population without seeing that structure would turn a
category name into an unexplained heuristic.

### Decision

Extend the existing Category tool with one-level, on-demand navigation: immediate
parents, immediate subcategories, QID-resolved article members and Back navigation.
Use native MediaWiki category metadata. Do not recursively crawl the category graph or
yet introduce a category-union population recipe.

### Generalization test

The browser accepts any content-category title and contains no History vocabulary. The
same page-metadata operations can later move behind the general MediaWiki provider and
serve another MediaWiki site.

### Revisit when

The observed History population needs a durable union of selected category branches,
or Wikipedia and Wikisource require the same browser with a configurable site.
