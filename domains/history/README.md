# History demonstration domain

> **Status: PARKED — 26 August 2026.** Superseded for now by the Rule Workbench demo
> release (Nobel + objectview 0.1.0). The model at
> `data/wikidata/history/history.model.json` validates, and one generation is saved
> (167 objects; 124 dated values carry `(Julian)`). See the status block in
> [`docs/roadmap-history-open-source.md`](../../docs/roadmap-history-open-source.md)
> for what History proved, where it stopped, and the open questions worth not
> rediscovering.

## Demonstration question

Can the source-to-snapshot engine construct an explainable model of people, events,
roles, places and documents around the **Revolutions of 1848**, using more than one
source while preserving the origin of every conclusion?

This is not intended to model all history. It is a compact forcing domain for the
open-source boundary described in
[`docs/open-source-boundary.md`](../../docs/open-source-boundary.md).

## Why this slice

The subject is understandable without specialist knowledge but its data is not flat:

- a person may participate in several events in different roles;
- offices and participation have dates and qualifiers, so they are statements rather
  than ordinary person fields;
- events connect people, places, organizations and documents;
- names, dates and roles may differ among sources;
- Wikipedia provides narrative/category/infobox evidence beyond a Wikidata property;
- Wikisource can later supply versioned primary-document evidence;
- the same entity can legitimately carry several kinds.

That combination exercises the engine more convincingly than a catalogue consisting
only of independent entities and scalar properties.

## Initial story

The first demonstrable path should answer questions such as:

- Which people participated in a selected 1848 event, and in what role?
- Which office did a person hold at the time?
- Where and when did the event occur?
- Which proclamations, constitutions or demands are associated with it?
- Which facts came from Wikidata, which were added or corroborated by Wikipedia, and
  which could later be supported by a primary text?

Representative examples for design and tests include Lajos Kossuth, Franz Joseph I,
the Hungarian Revolution of 1848, the broader Revolutions of 1848, and the Twelve
Points. Their exact identifiers, available properties and source coverage must be
verified during Explore; this brief deliberately does not freeze guessed QIDs or PIDs.

## Candidate model

```text
Person
  displayName
  alternateNames
  structuredName -> Name (owned)
  birthDate
  deathDate
  image

HistoricalEvent
  displayName
  startDate
  endDate
  locations -> Place
  participants -> Participation
  relatedDocuments -> HistoricalDocument

Participation (statement)
  event -> HistoricalEvent
  participant -> Person or Organization
  role -> Role
  startDate
  endDate
  source

OfficeHolding (statement)
  holder -> Person
  office -> Office
  jurisdiction -> Place
  startDate
  endDate
  predecessor -> Person
  successor -> Person

HistoricalDocument
  displayName
  date
  authors -> Person or Organization
  associatedEvent -> HistoricalEvent
  fullTextSource

Place
Organization
Office
Role
Name (owned)
```

This is a hypothesis to test in Explore, not yet a model file. In particular:

- `Participation` may need multiple value classes rather than only `Person`.
- Some roles may be better represented as selections over a statement field than as
  entity kinds.
- `HistoricalDocument` may begin as referenced metadata and gain Wikisource evidence
  only after the Wikidata/Wikipedia path is stable.
- Classes should be removed if they do not help demonstrate a public engine concept.

## Source roles

### Wikidata

Expected primary responsibilities:

- seed population and graph membership;
- stable entity identifiers and labels;
- dates, places, participants, offices and statement qualifiers;
- entity-kind evidence;
- Wikipedia sitelinks used to join the secondary source.

### Wikipedia

Expected additive responsibilities:

- discovered categories and infobox parameters selected by the user, not heuristics;
- aliases or descriptive facts only when explicitly bound;
- versioned source documents and evidence fragments for reviewed additions;
- a visible demonstration that one class can use more than one source.

The first version should choose only one or two Wikipedia bindings with measurable
yield. Adding a source merely because it is available would weaken the example.

### Wikisource (later experiment)

Candidate responsibility:

- full text and edition metadata for a proclamation, constitution or list of demands;
- text fragments corroborating a date, author or association;
- a primary-source lineage example distinct from encyclopedia evidence.

Wikisource is not required for the first runnable domain. Its purpose is to test the
provider boundary after Wikidata and Wikipedia work headlessly.

## First experiment: Explore before configuration

Use a deliberately small seed set containing approximately:

- 3–5 events;
- 10–20 people;
- 3–5 places;
- 2–5 documents;
- the statement records connecting them.

Explore should record, rather than assume:

1. candidate population relations and their coverage;
2. statement/qualifier shapes for participation and office holding;
3. entity kinds encountered in each role;
4. Wikipedia categories and infobox parameters observed in the sample;
5. available Wikipedia and Wikisource joins;
6. conflicts, missing values and duplicated referents.

The result of this experiment is the first model configuration plus a short decision
log explaining every retained class and source binding.

## Representative lineage scenarios

The acceptance snapshot should contain at least these scenarios:

1. **Structured fact:** an event date loaded from a declared Wikidata property.
2. **Qualified relationship:** a person's office or participation represented by a
   statement object with its qualifiers intact.
3. **Multiple kinds:** one entity admitted to more than one meaningful class without
   losing its canonical reference.
4. **Secondary-source addition:** a configured Wikipedia category or infobox value
   with page revision, retrieval time and evidence lineage.
5. **Missing fact:** an entity for which a requested value is known to have no answer,
   so Enrich does not request it forever.
6. **Recovered acquisition:** a batch that retries or splits and ultimately leaves the
   final snapshot complete.
7. **Future primary evidence:** one historical document whose Wikisource text could
   corroborate an existing conclusion without silently replacing it.

## Success criteria

- The domain is generated through the headless public facade.
- The explanatory view is produced from the same immutable plan that executes.
- The initial sample regenerates quickly enough for routine development.
- Every acquired field reports its source and, where applicable, document revision.
- Source yield makes it clear whether each Wikipedia binding paid for its cost.
- Participation and office statements preserve incomplete data rather than applying
  subject fallbacks that invent values.
- Owned `Name` instances keep their production-site identity and remain searchable.
- The saved snapshot reloads in ModelBuilder and TransformApp without special handling.
- Expanding the seed population changes scale, not semantics or configuration.

## Artifacts to add next

After the Explore experiment, this directory should gain:

```text
domains/history/
  README.md
  decisions.md
  expected-summary.json
  fixtures/
    representative-lineage.json
```

The generated model and snapshot should continue to live under the application's normal
domain storage until the open-source package defines a stable example-data convention.
