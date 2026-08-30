# Nobel Prizes: manual domain and configured domain

The hand-written `nobel` domain and the ModelBuilder walkthrough converge on nearly
the same served model. Comparing them makes the reusable configuration pattern
visible: the manual implementation expresses it through Java classes and parser
state; ModelBuilder expresses the source grain and field acquisition declaratively,
then Transform performs the domain aggregation.

## Served shapes

```text
Manual Java domain                         Configured domain

NobelPrize                                NobelPrize
├── year: int                             ├── year: Date
├── domain: Domain enum                   ├── category: Categories entity
└── laureatesWithMotivation[]             └── laureatesWithMotivation[]
    ├── laureates: Laureate[]                 ├── laureates: Laureate[]
    └── motivation: Motivation                └── motivation: Text

Laureate                                  Laureate role
├── name                                  ├── Wikidata identity + label
└── portrait                              └── Person / Organization by evidence
```

The structural agreement is the important result: a prize is identified by category
and year and contains several groups, each coupling a list of laureates to one
motivation.

## Correspondence

| Meaning | Manual implementation | ModelBuilder/Transform |
|---|---|---|
| Prize boundary | category/year header in the input text | group P166 records by `category + year` |
| Category | `NobelPrize.Domain` enum | `Categories` vocabulary of six Wikidata QIDs |
| Year | integer parsed from the header | P585 date qualifier, retaining source precision/calendar |
| Laureate identity | name-keyed `ManualEntity` | Wikidata QID and source label |
| Laureate group | names accumulated until a motivation line | P166 subject ∪ P1706 `together with` values |
| Motivation | following quoted line, parsed into `Motivation` | English P6208 monolingual-text qualifier |
| Duplicate/reciprocal grouping | parser block structure | canonical key over category, year, normalized laureates and motivation |
| Human/organisation | parser has separate name collections | positive P31 evidence produces modeled kinds |
| Portrait | separately downloaded by name | later datasource enrichment by stable entity identity |

## Construction pipelines

### Hand-written

```text
read source lines
  → recognize category/year header
  → accumulate person/organisation names
  → motivation line closes one group
  → append LaureatesWithMotivation to current NobelPrize
  → separately resolve portraits by name
```

The parser's control flow owns the schema. A blank line, quotation mark, heading text,
and the current mutable variables decide what each value means.

### Configured

```text
Categories vocabulary bounds P166
  → discover subjects carrying those award statements
  → reify each P166 statement
  → P166 value supplies category
  → P585 supplies year
  → subject ∪ P1706 supplies laureates
  → P6208 supplies motivation
  → canonical reconciliation removes reciprocal duplicates
  → Transform groups records by category + year into NobelPrize
```

Here the source grain, roles and qualification paths are explicit model facts. The
same acquisition/reification machinery can configure Oscars, offices held, marriages,
presidencies and other qualified relations without another parser-shaped domain.

## Genuine differences

The two results are not automatically identical:

- The manual source's category/year headings directly state the prize boundary;
  Wikidata represents awards as per-laureate P166 statements and requires
  reconciliation.
- The manual `MotivationParser` derives topics and keywords. The first configured
  version keeps the source text; topic extraction is a later, explicit enrichment.
- The manual domain identifies laureates by names. ModelBuilder uses QIDs, avoiding
  name collisions and making joins to other datasources possible.
- P585 is absent on two measured P166 statements. The configured pipeline reports
  this as source incompleteness rather than silently inventing a year.
- Wikidata may disagree with the manual source about co-laureates or wording. The
  comparison should report these differences, not force one representation to mimic
  the other silently.

## The Nobel API experiment

`nobel.api.NobelPrizeAward` independently confirms the same shape: category/year prize
→ achievements grouped by motivation → laureate awards. It is useful as a comparison
and possible corroborating source, but it is not part of the first configured domain
until domain-specific providers have a generic installation/configuration contract.

## What this comparison should test

After the configured domain is generated and transformed, produce a structural diff:

1. prize counts by category and year;
2. laureate groups per prize;
3. normalized participant sets per group;
4. motivation presence and text differences;
5. people versus organisations;
6. records excluded because category/year evidence is missing.

The goal is not byte-for-byte equality. It is to show that the generic pipeline
reconstructs the same domain structure while making source evidence, identity,
completeness and later enrichment explicit.
