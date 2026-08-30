# Nobel Prizes — first-release demo guide

This is the live ModelBuilder walkthrough and acceptance checklist. Every instruction
must name a control that exists, have a visible result, and work without editing JSON.
The reusable architecture motivated by this domain is described in
[Qualified relation events](qualified-relation-event-pattern.md).

## Source and model

The first release uses Wikidata only. A laureate's `award received (P166)` statement
already carries the Nobel category, year and—where recorded—the official award
rationale. ModelBuilder promotes each such statement into a domain record.

```text
NobelPrize                         StatementClass over P166
├── category       → Categories   statement value
├── laureate       → Laureate     statement subject
├── year                            P585 qualifier
└── motivation                      P6208 qualifier

Laureate                           role class
├── Person                         positive human evidence
└── Organization                   positive organisation evidence
```

Every P166 statement is already an award received, so there is no separate `won`
field. Wikidata has no consistently modelled prize share, so the first release does
not invent one.

Co-laureates are related by `together with (P1706)`. Shared achievements can later be
derived from repeated statement evidence, but grouping by motivation must wait until
its language is retained: independently normalizing English and Swedish text would
split translations of one rationale rather than identify one shared achievement. No
prize-share source entity is invented in the first release.

The isolated `nobel.api` experiment is not a configured ModelBuilder source. A
domain-specific provider will be reconsidered only after ModelBuilder has a generic,
explicit installation/configuration mechanism.

## Existing starting state

- Domain: `NobelPrizes`
- Root class: `NobelPrize`, configured as a Statement class over `P166`
- Vocabulary: `Categories`
- Configured field: `NobelPrize.category`, the P166 statement value
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
counts are what P166 yields for each value, and together they are the number Checkpoint 6
verifies against: **1033 statements over 633 distinct category/year pairs**.

`Q7191` "Nobel Prize" is deliberately NOT a member. It is the umbrella concept, and
four laureates carry it directly. Those statements are outside this demo's explicit
six-category scope; admitting the umbrella as a seventh category would make the counts
ambiguous without establishing that the statements mean one of the six prizes.

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

### Why `NobelPrize` is a Statement class

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

1. Start ModelBuilder and choose **NobelPrizes** from **Domain**.
2. In the configuration tree, select the root class **NobelPrizes**.
3. At the top of the configuration panel, change **Class kind** from
   **Source class** to **Statement class**.
4. In the Statement class panel, change **Class name** from `NobelPrizes` to
   `NobelPrize`.
5. Set **Reify from** to the empty entry. This means subjects are discovered directly from the
   statement property.
6. Set **Statement property** to `P166`.
7. Set **Value domain** to `Categories`.
8. Leave **Value type filter** empty.
9. Leave **Expansion policy** as `NONE`.
10. Press **Refresh derived view**.
11. Save the model.

Expected result:

- the class remains a StatementClass after navigating away and back;
- its derived recipe says subjects are discovered through P166;
- the value domain is `Categories`;
- validation no longer reports an unbounded discovered-subject statement class.

Do not generate yet. A statement value field and an explicit statement-subject field
are still required.

## Checkpoint 3 — configure the statement value

After Checkpoint 2 succeeds:

1. Add an entity field named `category` to `NobelPrize`.
2. In **Field definition**, set **Holds** to `Entity`.
3. Set **Of class** to `Categories`.
4. Set **Count** to `Single value`.
5. Leave **Display** as `Auto`.
6. In **Source**, leave **From** as `Wikidata` and set **Property** to `P166`.
7. Leave **Qualifier of** empty: this is the main statement value, not a qualifier.
8. Press **Apply field source**.

Expected result: the Statement class derived view recognizes `category` as the
statement-value field.

## Checkpoint 4 — configure laureate, year and motivation

Start with the explicit subject field:

1. Select `NobelPrize` and press **Add class**.
2. Name the new class `Laureate`. Keep it as a **Source class** and leave its
   population QID empty: the Nobel statements discover the entities that play this
   role, and Checkpoint 5 classifies their actual kinds from evidence.
3. Select `NobelPrize` and add a field named `laureate`.
4. Set **Holds** to `Entity`.
5. Set **Of class** to `Laureate`.
6. Set **Count** to `Single value`.
7. Leave **Display** as `Auto`.
8. Set **Load as** to `Statement subject`.
9. Leave **Property** and **Qualifier of** empty.
10. Press **Apply field source**.

This records that `laureate` is the entity carrying the P166 statement. It must not
be emulated with a made-up or usually-missing qualifier. The source also makes the
field an identity role automatically.

Now configure the date:

1. Add a field named `year` to `NobelPrize`.
2. Set **Holds** to `Date`.
3. Set **Count** to `Single value`.
4. Leave **Property** empty and set **Qualifier of** to `P585`.
5. Set **Qualifier time** to `DATE`. This retains the precision and calendar stated by
   Wikidata; a year-precision value still displays as a year.
6. Press **Apply field source**.

P585 is present on 1031 of the 1033 statements. The two without it are a visible gap,
not a configuration error.

Read the language note below, then configure the rationale:

1. Add a field named `motivation` to `NobelPrize`.
2. Set **Holds** to `Text`.
3. Set **Count** to `List`.
4. Leave **Property** empty and set **Qualifier of** to `P6208`.
5. Leave **Value language** empty; that control does not select a monolingual-text
   language.
6. Press **Apply field source**.

Optionally configure the explicitly stated co-laureates:

1. Add a field named `togetherWith` to `NobelPrize`.
2. Set **Holds** to `Entity`.
3. Set **Of class** to `Laureate`.
4. Set **Count** to `List`.
5. Leave **Property** empty and set **Qualifier of** to `P1706`.
6. Press **Apply field source**.

P1706 is present on 980 statements in the measured source data.

### The motivation is multilingual — say which wording you want

Wikidata states the rationale in about thirteen languages: **2041 values for 1033
statements** — en 1025, sv 857, nn 140, then ten more with fewer than ten each.

`P6208` is **monolingual text**, where the language belongs to the literal rather than
to a `P407` qualifier. The extraction carries that language through
(`MonolingualTextCodec`), so the field can choose:

1. Add a field named `motivation` to `NobelPrize`.
2. Set **Holds** to `Text` and **Count** to `Single value`.
3. Set **Qualifier of** to `P6208`.
4. Set **Value language** to `en`.
5. Press **Apply field source**.

Expected result: one rationale per award in English, stored as the text alone — the
language was how the wording was chosen, not part of what is served.

Leaving **Value language** blank keeps every wording, which is the honest answer when
none was asked for, but makes the field a collection of roughly two values saying the
same thing in different languages. A value stating no language is kept either way: it
contradicts nothing that was asked for.

## Checkpoint 5 — classify laureates

`Laureate` describes how an entity participates in a NobelPrize statement. Entity-kind
rules refine it using positive evidence:

- `P31 = Q5` → `Person`;
- configured organisation kinds → `Organization`;
- otherwise retain `Laureate` and report the unknown kind.

Never interpret “not known to be human” as evidence of an organisation.

## Checkpoint 6 — generate and verify

Generate with the explanatory pipeline visible, then verify at least:

- a prize shared by multiple people;
- different motivations within one category/year — with **Value language** set, each
  award holds one rationale, so differing counts mean differing achievements rather
  than differing languages;
- a Peace Prize awarded to an organisation;
- missing motivations remain visible gaps rather than dropped records;
- save/reload, TransformApp and the web client agree on the served records.

## Current status

- [x] `Categories` vocabulary contains six QIDs.
- [x] Wikidata statement model agreed.
- [x] Statement value-domain control is explicit in ModelBuilder.
- [ ] P166 and its qualifier structure verified through Explore.
- [x] Statement population declared in the saved NobelPrizes model.
- [x] Explicit statement-subject field source implemented.
- [x] `category` statement-value field configured.
- [ ] `laureate`, `year` and `motivation` fields configured.
- [ ] Laureate kinds configured.
- [x] Language selection for monolingual-text values.
- [ ] Domain generated and verified.
