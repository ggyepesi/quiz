# Office Holdings as History

## Status

Design note, no code. Says what the curated Position population is FOR, and what the
holdings over it measure out at before any of it is configured.

The configuration pattern itself is not invented here.
[[qualified-relation-event-pattern]] owns it — it already names `P39` with start/end,
predecessor and successor as a forcing example, and already calls the promoted record
`OfficeHolding`. This note supplies the population that bounds it, the direction of
acquisition, and the numbers. Companion to
[[finding-a-relevant-population]] (where the 1,317 offices came from),
[[bounding-an-entity-end]] (what bounds the object end) and
[[one-triple-per-class]] (what a statement class owns).

## The offices are a vocabulary, not a history

`Position` is 1,317 members: `Apostolic King of Hungary`, `Holy Roman Emperor`,
`khedive`, `Sapa Inca`, `exarch of Ravenna`, `zağarcıbaşı`. That answers *which offices
existed*. It answers nothing about who held them, when, or who followed whom — and those
are the questions a history quiz is made of.

The history is the **relation**: a person holds an office between two dates. In Wikidata
that is `P39 position held`, qualified. The office population's job is to bound it.

## Measured before modelling

```text
P39 statements whose object is one of the 1,317 offices   30,693
distinct people holding them                              21,529
offices actually held                                        966  of 1,317
```

Qualifier coverage over those holdings:

```text
P580  start time        20,293   65.8%
P582  end time          19,828   64.3%
P1365 replaces           9,213   29.9%
P642  of                     0    0.0%
```

Four things follow from those numbers, and each is a decision rather than a detail.

**The domain is Oscars-sized, not Position-sized.** 30,693 records over 21,529 people is
a normal generation, not the 92,300-member run that exhausts the heap (#191). The
curation that cut 92,300 offices to 1,317 is what makes the holdings affordable: the same
query against the old population would have dragged in every French commune mayoralty
and its holders.

**A third of the holdings have no dates.** "Who was Holy Roman Emperor in 1500" is
answerable for roughly two thirds of the record set and unanswerable for the rest — not
because the model is wrong but because Wikidata does not say. That is a coverage fact to
carry openly into the quiz layer, and a curation worklist, not something to paper over by
inferring dates from neighbouring holdings.

**Succession is thin.** `P1365 replaces` covers 30%, so a succession chain reconstructed
from that qualifier alone is full of holes. Where dates exist they order the holdings of
one office directly, and ordering by date is likely the better primary source for "who
followed whom", with `P1365` as corroboration rather than the other way round.

**`P642 of` is unused here — 0%.** It is a natural qualifier to reach for when an office
needs saying *of what*, and in this population nobody has used it. Configuring a field
for it would produce an empty column: the jurisdiction lives on the office
(`P1001`/`P17`), which is where the graph's evidence condition already reads it.

**351 offices were never held.** They are not defects. An office with no recorded holder
still answers "what was an exarch of Ravenna"; it simply cannot answer "who". Worth
knowing so the absence is not later read as a generation failure.

## The shape

One statement class, owning its triple:

```text
subject     Person       the human            ← arrives as the subject end
property    P39          position held
object      Position     ← BOUNDED by the 1,317
qualifiers  P580, P582   start, end           → "who held X in year Y"
            P1365, P1366 replaces, replaced by → succession, corroborating dates
```

The object end carries **population, never structure** ([[bounding-an-entity-end]]): the
bound says which entities may occupy that end, and the field on the record says where the
value is stored. Keeping those apart is what stops the office population quietly becoming
a claim about the `Position` class's own shape.

## Acquire from the offices, not from the people

Both directions reach the same statements and they are not equally good.

**From the offices (incoming `P39`)** — the office set is the curated thing. It bounds the
work to 30,693 statements by construction, `Person` arrives as the subject end rather
than as a population that must be bounded separately, and re-running after a change to
the office population changes exactly what it should.

**From a person population** — requires bounding "which people", which has no natural
answer, and then admits every `P39` they hold, including the mayoralties the population
work just removed. The bound would have to be re-imposed as a filter afterwards, which is
the shape this domain has already been burned by twice.

So the direction is a consequence of the curation, not a separate choice: having spent
the effort to make the office population right, it is the thing that should drive
acquisition.

## What this deliberately does not decide

The configuration UI, the validation, and the discovery/preview shape all belong to
[[qualified-relation-event-pattern]], which is a proposal for one reusable pattern across
Nobel, Oscars and History. Configuring `OfficeHolding` by hand first is reasonable — the
test fixtures already use that name and shape — but it should be read as the third
acceptance example for that pattern, not as a fourth bespoke domain.

Nothing here promotes a winner, a rank or a current-holder flag. A holding is a relation
with a start and an end; "current" is a question asked of it at read time, and inventing a
stored flag for it is the over-modelling the pattern note already warns about.

## Done when

- `OfficeHolding` generates roughly 30,000 records whose object is always one of the 1,317.
- `counts.tsv` shows the record count beside `Position=1317`.
- Date coverage is reported rather than assumed, so a quiz layer can tell "no answer
  recorded" from "no such holding".
