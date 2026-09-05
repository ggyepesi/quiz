# The Class Configuration Panels, As They Are

## Status

**Superseded as a drawing by `panel-layout.txt`, which is generated.** That file is what
the editors actually render, kept honest by `PanelLayoutIsCheckedInTest`; this note keeps
the reading of it — what diverges from the design and why. The drawings below are the
state on 2026-09-06, BEFORE the triple was unified; the divergences list is still current
except where marked.

A drawing, not a proposal. It records what the four kind editors render TODAY, after the
decomposition in `one-triple-per-class.md`, because the note said what each panel should
hold and never said in what order — and the implementation answered that question by
itself, four times, differently.

Read it beside `one-triple-per-class.md`, which is the design this is measured against.
The questions at the end are questions: they are not settled here.

## Source class — `ClassSourcePanel`

```text
┌─ Class: Constellation ──────────────────────────────────────────────┐
│ How do we find instances of this class?                             │  1
│ Class name: [Constellation]  Alias: [ ]  Extends: [(none)]          │  2  ClassHeaderEditor
│ Subtype:  [P31] = [        ] (label)                                │  3  discriminator
│ Represent matching entities as: [ordered list]                      │  4  role alternatives
│ Reifies statements of: [____________]                               │  5  free text; CHANGES THE KIND
│ ┌ Triple — subject · property · object ──────────────────────────┐  │  6  the triple, seventh
│ │ Subject:  this class's members                                 │  │
│ │ Property: [P31] [Find…] instance of                            │  │
│ │ Objects:  [Q8928 Q1053464] [Discover subtypes] [From parts…]   │  │
│ └────────────────────────────────────────────────────────────────┘  │
│ Exclude types: [__________]                                         │  7  population
│ Limit: [200] [x] Require label   lang: [en]                         │  8  population
│ [ ] Notable only (require Wikipedia article)                        │  9  population
│ Rank by: [(none)] [desc]                                            │ 10  population
│ Seed QIDs: [textarea]                                               │ 11  population
│ Search: [____] Method: [combo] [Search]                             │ 12  a discovery TOOL
│ │ results table                                             │       │ 13
│                                                    [Use selected]   │ 14
│ Identity & label                                                    │ 15  heading
│ Identity: Source entity (id + label)                                │ 16  a row named Identity
│ Source bindings: Wikidata entity · label                            │ 17
│ Additional names: [ ] Add aliases                                   │ 18
│ ┌ Display name: [Label] from field: [ ] template: [ ] ───────────┐  │ 19  DisplayNameEditor
│ ┌ Identity ─ key list, When the same key occurs, preview ────────┐  │ 20  a box named Identity
└─────────────────────────────────────────────────────────────────────┘
```

## Statement class — `StatementSourcePanel`

The only panel in the agreed order.

```text
│ Statement class: LaureatesWithMotivation                            │
│ Class name / Alias / Extends                                        │  ClassHeaderEditor
│ ┌ Statement triple — subject · property · object ────────────────┐  │  triple, second
│ │ ┌ Subject ─ Modelled as / Goes into field / Entities allowed ─┐ │  │
│ │ Subject population: [Person ▾]                                 │ │  │
│ │ Property: [P166]                                               │ │  │
│ │ ┌ Object ─ Modelled as / Goes into field / Entities allowed ──┐ │  │
│ ┌ Identity ─ key, reductions, preview ───────────────────────────┐  │
│ When duplicates occur (superseded): [KEEP_ONE]   (disabled)         │  a dead control, on screen
│ ┌ Display name ──────────────────────────────────────────────────┐  │
│ Canonical list: [(infer)]                                           │
│ ┌ Derived runtime recipe ─ Identity / fallbacks / key / quals ───┐  │  read-only
│ ┌ Graph discovery ─ Expansion policy / Resolved pattern ─────────┐  │  edit + read
```

## Owned class — `OwnedClassPanel`

```text
│ Owned class — instances are created by fields that target this class│
│ Class name / Alias / Extends                                        │
│ ┌ Display name ──────────────────────────────────────────────────┐  │  before the triple
│ ┌ Triple — subject · property · object (read-only, per site) ────┐  │  last
```

## Aggregate class — `AggregateClassPanel`

```text
│ Class name / Alias / Extends                                        │
│ From class: [LaureatesWithMotivation ▾]                             │  its triple, unnamed
│ Members field: [laureatesWithMotivation ▾]                          │
│ ┌ Grouped from (this class's field ← source class's field) ──────┐  │
│                                                                     │  empty grid row
│ ┌ Identity ──────────────────────────────────────────────────────┐  │
│ ┌ Display name ──────────────────────────────────────────────────┐  │
```

## Where this diverges from the design

1. **The triple is not first.** (Still true.) Only Statement leads with it. Source puts it seventh,
   behind two constructs that depend on it — the subtype narrows a membership, and
   "Represent matching entities as" is about the entities that membership admits. Owned
   puts it last, after the display name.
2. **`Reifies statements of:` is a free-text field that changes the class's kind.**
   Typing a class name into it on the SOURCE panel makes the class a Statement class. The
   kind is stored and chosen deliberately elsewhere; a class is picked from a list
   everywhere else; and `sourceClassName` already has an editor — it is the Statement
   panel's "Subject population". One field, two editors, one of which flips the kind by
   keystroke.
3. **Population is scattered across the triple.** The objects are in it; Exclude types,
   Seed QIDs, Limit, Require label, Notable only and Rank by are strewn before and after,
   with a search tool and a results table between them.
4. **Four panels, four orders** for the three shared components, so learning one panel
   does not help with the next. (Still true; the triple is now the same COMPONENT in all
   three that have one, but the panels still order their pieces differently.)
5. **Three headings for identity** on the Source panel: a section header "Identity &
   label", a row labelled "Identity:", and a box titled "Identity".
6. **Leftovers on screen**: a disabled "When duplicates occur (superseded)" control, and
   an empty grid row in the aggregate panel where the missing-key row used to be.

## The order the design implies

```text
header      name, alias, extends
triple      subject · property · object, with the population knobs that qualify it
identity    the key, and what happens when two candidates share it
display     how an instance is named
(reads)     derived recipe, resolved pattern — after the edits, marked as reads
```

## Open questions — not decided here

- **`Reifies statements of`**: delete it from the Source panel, leaving the kind switch
  as the only way to become a statement class? Or keep it as a read-only line that points
  at the Statement editor? Deleting is the smaller model; keeping a pointer is kinder to
  a reader who arrives on the wrong panel.
- **Search and its results table**: move out of the configuration form into their own
  place, or stay inline? They are a tool for finding a QID, not a property of the class,
  but they are also how the objects row gets filled today.
