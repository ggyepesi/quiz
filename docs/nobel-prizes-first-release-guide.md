# Nobel Prizes — first-release demo guide

This is the live ModelBuilder walkthrough and acceptance checklist. Every instruction
must name a control that exists, have a visible result, and work without editing JSON.
The reusable architecture motivated by this domain is described in
[Qualified relation events](qualified-relation-event-pattern.md). The corresponding
hand-written Java domain is compared field-by-field and phase-by-phase in
[Manual Nobel domain versus ModelBuilder](nobel-manual-vs-modelbuilder.md).

## Source and model

The first release uses Wikidata only. A laureate's `award received (P166)` statement
already carries the Nobel category, year and—where recorded—the official award
rationale. ModelBuilder first promotes each such statement into a
`LaureatesWithMotivation` record, then its provider-neutral Aggregate class groups
those records by category and year into one `NobelPrize`.

```text
NobelPrize                         Aggregate class: group by category + year
├── category       → Categories
├── year
└── laureatesWithMotivation → LaureatesWithMotivation[]
    ├── motivation                         P6208 qualifier
    └── laureates           → Laureate[]   P166 subject ∪ P1706 qualifier

Laureate                           role class
├── Person                         positive human evidence
└── Organization                   positive organisation evidence
```

Every P166 statement is already an award received, so there is no separate `won`
field. Wikidata has no consistently modelled prize share, so the first release does
not invent one.

Co-laureates are related by `together with (P1706)`. The visible participant list is
the statement subject plus those qualifier values, without privileging one laureate.
Several such participant groups may belong to the same category/year prize, and each
group keeps its own motivation. No prize-share source entity is invented.

This is deliberately a two-stage model:

1. **Source reification:** one P166 statement becomes one
   `LaureatesWithMotivation` candidate. Its served fields are `laureates` and
   `motivation`; category and year are retained as aggregation evidence.
2. **Domain aggregation in ModelBuilder:** candidates are reconciled, then
   grouped by the P166 value and P585 qualifier into one `NobelPrize`, whose visible
   fields are `category` and `year`.

The first stage is a Statement class. The second is an Aggregate class: aggregation
changes the served domain shape without acquiring another source entity. Both are
explicit model configuration and both replay during Remap.

The isolated `nobel.api` experiment is not a configured ModelBuilder source. A
domain-specific provider will be reconsidered only after ModelBuilder has a generic,
explicit installation/configuration mechanism.

## Existing starting state

- Domain: `NobelPrizes`
- Current saved root class: `NobelPrize`
- Current P166 Statement class: `NobelAwardStatement`, to be renamed
  `LaureatesWithMotivation` at the continuation point below
- Vocabulary: `Categories`
- Configured field: the statement class's `category`, the P166 statement value
- Category values:

| QID | Wikidata label | awards |
|---|---|---|
| Q38104 | Nobel Prize in Physics | 231 |
| Q80061 | Nobel Prize in Physiology or Medicine | 233 |
| Q44585 | Nobel Prize in Chemistry | 200 |
| Q37922 | Nobel Prize in Literature | 122 |
| Q35637 | Nobel Peace Prize | 148 |
| Q47170 | Prize in Economic Sciences in Memory of Alfred Nobel | 99 |

The labels are the ones Wikidata carries, so a search in Explore finds them. The award
counts are what P166 yields for each value, and together they are the number Checkpoint 7
verifies against: **1033 statements over 633 distinct category/year pairs**.

`Q7191` "Nobel Prize" is deliberately NOT a member. It is the umbrella concept, and
four laureates carry it directly. Those statements are outside this demo's explicit
six-category scope; admitting the umbrella as a seventh category would make the counts
ambiguous without establishing that the statements mean one of the six prizes.

## Restart the configuration cleanly

Keep the initial root class and rename it `NobelPrize`. Then follow Checkpoints 2–5.
The important ownership distinction is:

- P166 reification retains category and year as source/staging facts;
- `LaureatesWithMotivation` serves laureates and motivation;
- Transform owns the explicit category/year aggregation into `NobelPrize`.

## Checkpoint 1 — discover the source structure

Do not begin by typing a PID from this guide. Establish it from a concrete Wikidata
example:

1. Start ModelBuilder and choose **NobelPrizes** from **Domain**.
2. Open **Explorer tools**, then **Wikidata → Explore → Entity**.
3. Search for `Albert Einstein` and select the result `Q937`.
4. Press **Explore entity relations**.
5. In **Relations**, search for the exact PID `=P166`, or for `award received`.
6. Select the `award received (P166)` relation. Confirm that one of its values is
   **Nobel Prize in Physics (Q38104)**, a member of the configured `Categories`
   vocabulary.
7. Open Einstein's QID link and inspect that particular award statement. Its attached
   facts include `point in time (P585)` and `award rationale (P6208)`.

What this establishes:

- the laureate is the statement **subject** (`Albert Einstein`);
- `P166` is the statement **property** (`award received`);
- the Nobel category is the statement **value** (`Nobel Prize in Physics`);
- year and motivation belong to that particular award as **qualifiers**.

### Why `LaureatesWithMotivation` is a Statement class

A source class represents a Wikidata entity with a QID. There is no separate Wikidata
item for “Einstein receiving the 1921 Physics prize” that can serve as this record's
identity. The record is the P166 statement itself.

Modelling only the laureate would also be lossy: one person may receive several awards,
and every award carries its own year and motivation. Those facts must not be flattened
onto one `Person` instance. A Statement class preserves one record per statement and
keeps its qualifiers attached to the correct award.

Its stable grain is therefore:

```text
statement subject + statement value + distinguishing qualifiers
laureate          + category        + year
```

The `Categories` vocabulary bounds discovery to the six Nobel award values. Without
that bound, “all P166 statements” would mean every award of every kind in Wikidata.

## Checkpoint 2 — declare the statement population

Perform this checkpoint before adding fields.

### Discover the statement property

The configuration uses `P166`, but the reader should establish that identifier from
source evidence rather than copy an unexplained PID from this guide:

1. Open **Explorer tools → Wikidata → Explore → Entity**.
2. Search for `Albert Einstein` and select **Albert Einstein (Q937)**.
3. Press **Explore**.
4. In the resulting **Relations** list, search for the exact PID `=P166` (or search
   for `award received`).
5. Select **award received (P166)** and inspect its values. Confirm that
   **Nobel Prize in Physics (Q38104)** is among them and belongs to the configured
   `Categories` vocabulary.
6. Follow Einstein's QID link and inspect that P166 statement on Wikidata. The
   statement carries **point in time (P585)** and **award rationale (P6208)** as
   qualifiers. This is why the model needs a Statement class rather than a scalar
   `awards` field on a person.
7. Record or add the selected property to **Reusable selections** as `P166 — award
   received`, then return to the model configuration. Reusable selection is an
   explicit handoff; merely highlighting the relation does not modify the model.

The discovered roles are now concrete: the subject is the laureate, P166 is the
statement property, its value is the Nobel category, and P585/P6208 describe that
particular award statement.

1. Start ModelBuilder and choose **NobelPrizes** from **Domain**.
2. In a clean model, select the root class **NobelPrizes** and rename it
   `NobelPrize`.
3. With `NobelPrize` selected, press **Add class** and name the new class
   `LaureatesWithMotivation`. If continuing from the saved demo state, rename the
   existing `NobelAwardStatement` instead of adding another class.
4. Select `LaureatesWithMotivation` and change **Class kind** from **Source class**
   to **Statement class**.
5. Set **Reify from** to the empty entry. This means subjects are discovered directly from the
   statement property.
6. Set **Statement property** to the discovered `P166`.
7. Set **Value domain** to `Categories`.
8. Leave **Value type filter** empty.
9. Leave **Expansion policy** as `NONE`.
10. Press **Refresh derived view**.
11. Save the model.

Expected result:

- `NobelPrize` remains the root and `LaureatesWithMotivation` remains a Statement
  class after navigating away and back;
- its derived recipe says subjects are discovered through P166;
- the value domain is `Categories`;
- validation no longer reports an unbounded discovered-subject statement class.

Do not generate yet. The symmetric participant and motivation fields are still
required.

## Checkpoint 3 — declare the category staging fact

1. Add an entity field named `category` to `LaureatesWithMotivation`.
2. In **Field definition**, set **Holds** to `Entity`.
3. Set **Of class** to `Categories`.
4. Set **Count** to `Single value`.
5. Leave **Display** as `Auto`.
6. In **Source**, leave **From** as `Wikidata` and set **Property** to `P166`.
7. Leave **Qualifier of** empty: this is the main statement value, not a qualifier.
8. Press **Apply field source**.

Expected result: the Statement class recognizes `category` as its P166 value field.
Checkpoint 6 explicitly maps this staging fact onto `NobelPrize.category`.

## Checkpoint 4 — configure laureate, year and motivation

Start with the participant type:

1. Select `LaureatesWithMotivation` and press **Add class**.
2. Name the new class `Laureate`. Keep it as a **Source class** and leave its
   population QID empty: the Nobel statements discover the entities that play this
   role, and Checkpoint 5 classifies their actual kinds from evidence.

The statement subject remains an internal part of the P166 statement's identity and
provenance. It enters the visible model through the symmetric `laureates` list below,
not through a privileged scalar field.

The date is acquired on the source statement and later mapped to the outer prize:

> **Discovering P585:** return to the Einstein `award received (P166)` statement
> inspected in Checkpoint 2. Its qualifier labelled **point in time** links to
> property `P585`. Qualifiers describe that particular award statement, so configure
> it under **Qualifier of**, not as the field's main **Property**.

1. Add a field named `year` to `LaureatesWithMotivation`.
2. Set **Holds** to `Date`.
3. Set **Count** to `Single value`.
4. Leave **Property** empty and set **Qualifier of** to `P585`.
5. Set **Qualifier time** to `DATE`. This retains the precision and calendar stated by
   Wikidata; a year-precision value still displays as a year.
6. Press **Apply field source**.

P585 is present on 1031 of the 1033 statements. The two without it are a visible gap,
not a configuration error.

Configure the symmetric laureate list:

1. Add a field named `laureates` to the statement class.
2. Set **Holds** to `Entity`.
3. Set **Of class** to `Laureate`.
4. Set **Count** to `List`.
5. Leave **Property** empty and set **Qualifier of** to `P1706`.
6. Set **Load as** to `Statement participants`.
7. Press **Apply field source**.
8. Leave **Canonical list** unchanged. It controls source-copy reconciliation, not
   the domain aggregation described below.

The resulting value is `P166 statement subject ∪ P1706 values`, deduplicated by QID.
The subject remains internally available for statement identity and provenance, but it
is not presented as a privileged laureate. P1706 contributes 980 qualifier values,
but those occur on 640 statements; 393 statements have none and still produce a
one-member list.

Set the Statement class display-name template to
`{laureates} — {category}`. This is evaluated after final reference-label hydration,
so cards and collision reports say `Gérard Mourou — Nobel Prize in Physics`, not
`Gérard Mourou — Q38104`.

### The motivation is multilingual — say which wording you want

Wikidata states the rationale in about thirteen languages: **2041 values for 1033
statements** — en 1025, sv 857, nn 140, then ten more with fewer than ten each.

`P6208` is **monolingual text**, where the language belongs to the literal rather than
to a `P407` qualifier. The extraction carries that language through
(`MonolingualTextCodec`), so the field can choose:

1. Add a field named `motivation` to `LaureatesWithMotivation`.
2. Set **Holds** to `Text` and **Count** to `Single value`.
3. Set **Qualifier of** to `P6208`.
4. Set **Value language** to `en`.
5. Press **Apply field source**.

After all four fields exist, return to `LaureatesWithMotivation`. Under
**Canonical identity → Same record when**, select `category`, `year`, and
`motivation`. Set **When duplicates occur** to `Merge records`, then press
**Refresh derived view**. This is the Statement-class action that banks the canonical
controls; there is no separate “Apply class configuration” button.

The key states the grain in domain language: one record is one motivation for one
category and year. `laureates` is deliberately not identity; Wikidata can describe
the same achievement from several laureate statements whose participant lists are
partial. **Merge records** unions those lists while retaining the preferred record's
scalar values. Existing statement classes default to **Keep one**, preserving their
historic behavior.

Expected result: one rationale per award in English, stored as the text alone — the
language was how the wording was chosen, not part of what is served.

Leaving **Value language** blank keeps every wording, which is the honest answer when
none was asked for, but then **Count** must be `List`: the field contains roughly two
values saying the same thing in different languages. When a requested language has no
tagged answer, an untagged value is used as the fallback; it never displaces an exact
language match.

## Checkpoint 5 — classify laureates

`Laureate` describes how an entity participates in a P166 statement. Entity-kind
rules refine it using positive evidence:

- `P31 = Q5` → `Person`;
- configured organisation kinds → `Organization`;
- otherwise retain `Laureate` and report the unknown kind.

Never interpret “not known to be human” as evidence of an organisation.

### Give people a structured name and portrait

These are explicit `Person` fields, not hidden consequences of Wikidata identity or
label acquisition. Reuse the same owned-name shape as the Oscars domain.

First create the owned component:

1. Add a class named `Name`.
2. Set **Class kind** to **Owned class**. Leave its population/source mapping empty:
   a `Name` is produced for its owning `Person` and inherits that person's QID for
   acquisition.
3. Add `familyName` to `Name`: **Holds** `Entity`, no **Of class**, **Count** `List`,
   **Property** `P734` (`family name`).
4. Add `givenName` to `Name`: **Holds** `Entity`, no **Of class**, **Count** `List`,
   **Property** `P735` (`given name`).

Then attach it to people:

1. Add `structuredName` to `Person`.
2. Set **Holds** to `Entity`, **Of class** to `Name`, and **Count** to
   `Single value`.
3. Configure it as an **Owned component**. Do not enter a property: the nested
   `Name` fields fetch P734 and P735 using the owning Person's QID.

Finally add the portrait:

1. Add `portrait` to `Person`.
2. Set **Holds** to `Image` and **Count** to `List`. Wikidata can carry more than
   one ranked P18 image, so the source should not be forced into a false scalar.
3. Set **Property** to `P18` (`image`) and apply the field source.

Expected result: a Person card keeps Wikidata's label as its display name, exposes a
searchable/expandable structured name, and renders available P18 values as images.
The remaining generic `Laureate` instances do not inherit these Person-only fields;
organisation presentation can be configured after their positive P31 kinds are
identified.

## Checkpoint 6 — configure the aggregate prize

Select `NobelPrize` and change **Class kind** to **Aggregate class**. Add these fields
before completing the aggregate recipe:

1. `category`: **Holds** `Entity`, **Of class** `Categories`, **Count**
   `Single value`; leave its source property empty.
2. `year`: **Holds** `Date`, **Count** `Single value`; leave its source property
   empty.
3. `laureatesWithMotivation`: **Holds** `Entity`, **Of class**
   `LaureatesWithMotivation`, **Count** `List`; leave its source property empty.

In the Aggregate-class panel set:

- **From class:** `LaureatesWithMotivation`
- **Members field:** `laureatesWithMotivation`
- **Group by:** `category=category` and `year=year`
- **Missing key:** `EXCLUDE`

Under **Display name fields**, check `category` and `year`, then press
**Apply aggregate class**. The editor assembles the title in field order and shows an
em dash between values; the user does not enter template syntax.
Do not configure a second canonical key: the aggregate's **Group by** mappings are
already its identity authority, and the compiler deliberately keeps the ordinary
canonical-key list empty for aggregate classes.

The resulting offline domain-shaping operation is:

```text
group LaureatesWithMotivation by category + year

→ NobelPrize
  ├── category
  ├── year
  └── laureatesWithMotivation: List<LaureatesWithMotivation>
```

The operation excludes a source record missing category or year rather than creating
a phantom “unknown-year NobelPrize”. It preserves every
`LaureatesWithMotivation` record as a structured member: motivation is never
flattened onto the prize and laureates are never merged into one undifferentiated
year-wide list.

The aggregate is a domain-derived class, not another Wikidata Statement class. Its
identity is the configured grouping key `category + year`; those fields are owned by
`NobelPrize`, while its members retain their statement identifiers and provenance.

## Checkpoint 7 — generate and verify

Generate with the explanatory pipeline visible, then verify at least:

- a participant group shared by multiple people;
- one category/year prize containing multiple participant groups with their own
  motivations;
- different motivations within one category/year — with **Value language** set, each
  award holds one rationale, so differing text means differing achievements rather
  than differing languages;
- a Peace Prize awarded to an organisation;
- missing motivations remain visible gaps rather than dropped records;
- save/reload, TransformApp and the web client agree on the served records.

## Current status

Verified against the saved model and the generated snapshot on 2026-08-31.

**Done**

- [x] `Categories` vocabulary contains the six category QIDs.
- [x] Wikidata statement model agreed.
- [x] Statement value-domain control is explicit in ModelBuilder.
- [x] `P166` and its qualifier structure verified through Explore and by measurement:
      1033 statements, 1031 with `P585`, `P6208` in about thirteen languages.
- [x] Statement population declared in the saved model — `LaureatesWithMotivation`
      reifies `P166` bounded by `Categories`.
- [x] Statement subject composed into an explicit symmetric participant list
      (`STATEMENT_PARTICIPANTS`).
- [x] `category` and `year` retained as aggregation evidence.
- [x] `motivation` configured, `P6208` with **Value language `en`**.
- [x] Language selection for monolingual-text values.
- [x] Identity settled: the grain is `category + year + motivation`; participants are
      unioned by the duplicate policy rather than identifying a record.
- [x] Transform aggregation configured and verified — `NobelPrize` aggregates
      `LaureatesWithMotivation` by category and year, excluding records with no year.
- [x] Domain generated and verified: **989 Person, 33 Laureate, 716 awards, 634 prizes,
      6 categories**. 716 is exactly Wikidata's distinct category/year/motivation count;
      558 prize-years resolve to one achievement.
- [x] Registered for serving in `data/wikidata/datasets.json` with its model, ruletree
      and snapshot paths and all four types.

**Open**

- [ ] **Serve it and look at it.** The dataset is registered, but no one has loaded this
      domain in the web client. `NobelPrize` is an aggregate class, a shape no previously
      served domain has, so this is the step with real unknowns rather than real work.
- [ ] **Enrich `Person`.** It has zero fields today. Coverage over the 990 human
      laureates is essentially complete and makes the demo look finished:

      | field | property | coverage |
      |---|---|---|
      | birth date | P569 | 990 / 990 |
      | citizenship | P27 | 990 / 990 |
      | given name | P735 | 977 |
      | portrait | P18 | 970 |
      | death date | P570 | 696 (the rest are living) |

- [ ] **Classify the 33 non-people.** A `Q5` entity-kind rule produces `Person`; the
      organisations that win the Peace Prize stay in the generic `Laureate` bucket
      instead of an organisation kind.
- [ ] **Clean the dataset registry.** A stale `nobel-prizes` entry from 2026-08-02 points
      at a snapshot that no longer exists and claims the same `rootClass` as the real
      entry. `President` and `SportTeam` have the same collision (×2 and ×3).

## What the remaining numbers mean

The 78 prize-years holding more than one record are the source disagreeing with itself,
not the model:

- **74** genuinely have several achievements — including years where Wikidata hangs the
  prize-level motivation on one arbitrary laureate, as it does to Mourou for Physics 2018.
- **4** are laureates whose rationale nobody recorded: Peace 1997, 1917, 1944 and 1963.

Both stay visible as gaps rather than being merged into a guess. Two awards state no year
and therefore belong to no prize, which is what the aggregate's missing-key policy says
out loud.
