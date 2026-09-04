# Modelbuilder constructs — informal interpretations

This is the intuition-level companion to the code. It reads each modelbuilder
construct (**Class**, **Statement**, **Selection**) the way you'd explain it out
loud, and ties every reading back to the concrete type that implements it. It is
deliberately informal; for exact behaviour see the classes referenced inline.

Two questions define everything you configure:

1. **How are a class's members gathered?** — its *membership*.
2. **How is one member told apart from another?** — its *identity*.

Membership can be **declared** (you state a rule) or **derived** (it falls out of
something else). Identity is one of two regimes: **QID** (a real Wikidata entity)
or **content** (a natural key over field values). Fields are a third, separate
thing: *attributes*, never identity for an entity class — additive enrichment.

---

## Vocabulary — one word per concept

**The rule: the same words for the same semantic concept.** This is the counterpart of
"look for a refactorable implementation". When you catch yourself inventing a phrase for
something the model already names, the name usually exists and you have not looked — and
a second name for one concept is the same latent bug as a second discovery path for one
fact, just in prose.

Where Wikidata already has a word, that word wins. Where two things differ, they get two
names, however similar they look.

### The triple

Wikidata is statements, and a statement is a **triple**:

```text
subject ── property ──▶ object
```

RDF names the three *positions* subject, **predicate**, object. Wikidata's name for the
**entity filling** the predicate position is a **property** (P39 is a property). So
`subject · property · object` is the vocabulary here, and "predicate" is not a competing
name for it — it names the slot, "property" names what sits in the slot.

A statement class configures exactly one triple. Everything else it carries — qualifiers,
rank, canonical key, display — is said *about* that triple and does not redefine it.

### Direction belongs to the traversal, not to the triple

One triple is **outgoing** from its subject and **incoming** to its object at the same
time. Direction is therefore not a property of the statement; it is a property of the
walk, relative to whichever class you are standing on.

```text
⟨subject, property, ?⟩     outgoing, seen from the subject
⟨?, property, object⟩      incoming, seen from the object
```

So the two pairs are not synonyms and neither replaces the other: **subject/object are
absolute positions in the triple; incoming/outgoing are relative to the class you are
looking from.** `RuleDirection` is genuinely a direction and must not be renamed to
subject/object.

This is also what the two acquisition strategies actually are:

```text
read from a subject population   start at the subjects, read outgoing
discover from the property       start at the objects,  read incoming
```

Which explains a rule that otherwise reads as arbitrary: a discovering statement class
**must** bound its objects. Discovery starts from the object side, so the object
population *is* the starting set — unbounded, it is a scan of Wikidata.

### Two ways a class name is made concrete — and they are not the same thing

Both the subject and the object are named by a class that is a **placeholder** for a
population. Two different mechanisms make a placeholder concrete. They are **orthogonal
axes, not one per leg** — either can apply at either end:

| mechanism | when it acts | what it answers |
|---|---|---|
| **bounding** — allowed subjects / allowed objects | *before* acquisition | which entities are fetched at all. Seeds, a vocabulary, or an already-extracted population. |
| **represented as** | *after* acquisition, per instance | what a fetched instance turns out to be. `PositionHolder` represented as `Person` when P31 contains Q5; an instance failing the evidence stays a `PositionHolder` — not discarded, not falsely stamped. |

The two must not share a word. "Extended via its type" would name both and is therefore
exactly the mistake this section exists to prevent: one bounds a query, the other
classifies a result.

Which end gets bounded is a modelling choice, and **both ends at once is the good case**,
not a special one: see [[sparql-generation-rules.md]] R16 — a join anchored on only one
side spans all of Wikidata for that property, soft-times-out, and returns a different
partial row set each run (the 11076-vs-11142 drift). When both sets are known, bind both.

"Represented as" is already the model's word (`EntityRepresentationRule`); it needs
using, not inventing. See [[contextual-entity-representation.md]].

### "Type" is retired

`type` is the worst offender in the codebase for this rule. It means at least six things:
`FieldType` (a value's shape), `objectview.FieldKind` (the same idea, named twice),
`ClassKind`, an instance's `typeKey` stamp, P31 values (`"Wikidata type/class:"` — a
label already hedging by saying both), and a served quiz collection.

Four things are hiding under it, and **all four already have a word** — in Wikidata or in
this codebase. Nothing here is invented; it is a survey, which is the point:

| concept | word | where the word already is |
|---|---|---|
| the shape of a value | **datatype** | Wikidata's own: properties are `WikibaseItem`, `String`, `Time`, `CommonsMedia`, … (`properties.tsv` column 4) |
| what P31 evidence says an entity *is* | **kind** | `EntityKindRule` — *"maps Wikidata evidence (normally P31 values) to one modeled entity kind"*; `MembershipPattern.EVIDENCE_KIND` |
| a modeled population | **class** | `GeneratedClassModel` — `Person`, `Nominee`, `PositionHolder` |
| Source / Statement / Owned / Aggregate | **construct** | `CLAUDE.md` and the README already say construct |

So `Nominee.type` — the field holding P31 values such as Q5 — is `Nominee.kind`. And
`FieldType` and `objectview.FieldKind` collapse into one **datatype**, which was a
separate cleanup and is now a consequence.

This table replaces an earlier draft here that offered *class* for the P31 answer and
*kind* for our internal shapes. That draft was written without surveying `EntityKindRule`,
which had already answered the question — the failure this whole section warns about,
committed in the section itself. Worth leaving on the record.

### Words to retire

The vocabulary below is the target. **The code and UI do not conform yet** — this table
is what a rename converges on, not a description of today.

| retire | use | why |
|---|---|---|
| "Reify from" | **subject population** | it names a source of subjects, not the subject |
| "Statement property" | **property** | the triple already says which property |
| "Value domain" | **allowed objects** | says what it does |
| "Value type filter" | **object type filter** | it filters the object |
| "subject entity", "statement subject" | **subject** | |
| "statement value", "object entity/value" | **object** | |
| `ROOT_TO_ITEM` / "root → item" | **outgoing** | root/item is neither position nor direction |
| `ITEM_TO_ROOT` / "item → root" | **incoming** | |
| "type" (of an entity), "Wikidata type/class" | **kind** | what P31 evidence says it is; `EntityKindRule` already |
| "type" (of a field), `FieldType` vs `FieldKind` | **datatype** | Wikidata's word for a value's shape |
| `ClassKind` | **construct** | Source / Statement / Owned / Aggregate |
| "record" | **instance** | one noun for a class's members |

Direction is the cheapest first target: three vocabularies exist for it today — the enum
constants, the enum's own preview text, and `"incoming"/"outgoing"` in the advisor — and
the correct word is already one of them.

**Settled: instance.** A class's members are *instances*, statement classes included.
"Record" is retired. A statement class's members differ from an entity class's only in
identity regime — a content key rather than a QID — and that is a fact about identity,
not a reason for a second noun.

---

## Class (`GeneratedClassModel`)

A class is a named kind of thing plus the fields you load on it. Its membership
"shape" is classified by `MembershipPattern` and shown on the class-tree node.

### Entity classes — source identity by default

An entity class is produced from source entities. Its default key is the
provider-qualified source identity — for Wikidata, the QID — so every reference to the
same source entity resolves to one modeled instance. A modeller may instead choose a
content key. The shared reducer then combines matching normalized candidates while the
canonical result retains every contributing source identity; the saved acquisition
candidates keep their QIDs for later Enrich and Remap.

Declared-membership entity classes gather members by a rule:

- **By type** — `P31 = Qx` (`SINGLE_TYPE`), or `P31 ∈ {type, subtypes}` (`MULTI_TYPE`).
- **By relation** — a non-P31 relation into a target set, e.g. `P1411 → the award
  categories` (`SINGLE_TARGET_RELATION` / `MULTI_TARGET_RELATION`).
- **Seeded** — an explicit curated QID list (`SEEDED`).

### Referenced-only classes — *derived* membership (the `Nominee` / `ForWork` case)

Some entity classes have **no membership rule of their own** — their membership is
**derived** from a field that points at them. `Nomination.forWork` says "the value
of the `P1686` qualifier"; **`ForWork` is exactly the set of entities at the
value-end of that**. The configuration that defines the class lives on the
**referencing field**, one level up. So the class is a **typed slot** = *the range
of `P1686` as used by `forWork`* (if two fields point at it, the union of their
ranges).

This is the same principle as the reify value-filter
([[reification-value-filter.md]]): **don't re-specify what's already implied —
derive it**, or the two copies drift.

Consequences of this framing:

- Such a class is an **identity holder**: until you add fields it holds only the
  QID (+ label + `wikidata` link). That is *not* "unconfigured" — a QID-identity
  class is **complete the moment a field points at it** (identity intrinsic,
  membership derived, fields additive). "Unconfigured" on the tree node is a
  category error; the tree node names the deriving field instead —
  *"Derived from Nomination.forWork (P1686)"* (`MembershipPattern.REFERENCED`).
- Even fieldless, it is **load-bearing**: it is the **join key** for the inverted
  views — "all nominations of a nominee", "every nomination for a work" are
  inverts *over the shared QID identity*. No identity holder, nothing to group by.
- **Fields on it** (e.g. `genre` on `ForWork`) are not membership — they are
  *properties of the entities in the derived set*, loaded per-referent. See
  [Discovering field properties](#discovering-field-properties) below.

### Owned components — one projected object per owner

Select **Owned class** as the target class's class kind. This records only the
population kind — never an owner. The Owned class editor consequently contains
only its structural settings (class name, alias and base class). An Owned class
may extend another Owned class, inheriting its fields and owner-QID identity
semantics. Source and Statement classes cannot be Owned bases because their
population/identity grains are different.

An ENTITY field whose target is an Owned class composes one target object from
each owner. On that field, explicitly choose **Owned component (QID from
owner)**; this is the persisted owner-QID contract, not an inference from the
target class.
For example, `Person.structuredName → Name` creates a
`Name` with the Person's QID, then loads `Name.givenName (P735)` and
`Name.familyName (P734)` against that QID. The field is the production site, so
`Name` does not repeat an owner-class setting or define a membership query.

The stable identity includes the site — `⟨Name@Person.structuredName, Q42⟩` — so
two component fields targeting `Name` do not collapse. Subclasses of `Person`
are admitted automatically. If a class extends `Name`, it already inherits
Name's fields and should not also compose a nested Name; validation rejects that
redundant/cyclic shape.

---

## Statement (reified class — `StatementClassSource` + `ReifyConstruct`)

A **statement class** (e.g. `Nomination`) is the promotion of a property's
*statements* into first-class records. `reifiesStatements()` is true; the
`MembershipPattern` reads `REIFIED`.

### Content identity (a natural key)

A nomination **is not a Wikidata entity** — there is no QID to hold — so it can
only be identified by **its content**: the tuple of its field values
(nominee + category + year …). Its identity is *intensional*, derived from what it
contains. In the model this is `CanonicalSpec` with `kind = DERIVED` over
`keyFields` (contrast the entity classes above, which are QID-keyed). See the
"Canonical identity" editor in `StatementSourcePanel` and
[[canonicalization-model.md]].

### What configures it

Configuring a statement class is four decisions, in this order:

1. **name the subject and the object** — two placeholder classes. Neither asserts what
   its instances are; that is settled afterwards by *represented as*, if at all;
2. **choose the property** — the PID joining them;
3. **bound one end or both** — allowed subjects, allowed objects, or (preferably) both;
4. **say where the subject goes** — which field receives it, since a subject has a place
   in the instance just as the object does.

Steps 1–3 are the triple. Step 4 is the one the editor does not own today: the subject's
destination is configured on the *field*, which is why the panel named after the
statement shows every leg but that one. Bounding both ends in step 3 is the good case,
not an exotic one — see R16 above.

`StatementClassSource` carries:

- the **property** (`propertyPid`, e.g. `P1411`) — a statement class keys on this,
  never on where its subjects came from;
- an optional **subject population** (`sourceClassName`) — already-extracted members
  whose statements are read *outgoing*. Blank ⇒ subjects are discovered *incoming*
  from the property (`PopulationSubjectLoader`);
- **allowed objects** (`valueSelection`) — a VOCABULARY Selection bounding which
  entities may fill the object position, replacing a filter that would otherwise be
  re-specified. Required when subjects are discovered, because then the objects are
  the starting population.

Both bullets two and three describe the same triple from opposite ends — see *Direction
belongs to the traversal, not to the triple* above. They are acquisition
strategies, not two kinds of statement class.

Its fields come from the statement's **object** and its **qualifiers** (e.g.
`nominee ← P2453`, `forWork ← P1686`, `year ← P585`), plus post-transform facts
like `won` (a companion match). See
[[modelbuilder-qualifier-cheatsheet.md]] and
[[statementclass-explicit-roles.md]].

---

## Selection (`Selection`, `Kind`)

A **Selection** is a *named set over the entity pool that is referenced but never
served* — it is not a product class, it shapes how other things load or render.
Its content is inspectable in the Selections viewer (`SelectionViewerPanel`,
resolved by `SelectionContentResolver`).

Two concrete subclasses (`VocabularySelection` / `PopulationSelection`), so each
carries only its own fields and illegal combinations are unrepresentable:

- **`VocabularySelection`** — a named QID set with **two roles**, told apart by
  where a field's value sits relative to the set:
  - **allowed values** — the value *is a member*. `category → OscarCategories`: the
    value *is* "Best Picture", one of the 59. Homogeneous (a fixed instance set);
    the same bound a statement class applies to its object position, where it is
    called **allowed objects** — one vocabulary serving a field slot and an object
    slot, never duplicated.
  - **union tag set** — the value *is an instance-of a member*. `nominee →
    NomineeTypes`: the value is Meryl Streep, whose `P31` *is* "human", one of the
    tags. The members are *types*, not values (see below).
- **`PopulationSelection`** — a **subject set**: the entities matching a membership
  relation (`relationPid` into `targetQids`), for a reify to draw its subjects from
  — e.g. "the entities with `P1411` into the Oscar categories" (the nominee
  subjects). Browsable (sampled) in the viewer via the resolver's POPULATION path.

A Selection is the right home for a **closed, known vocabulary**; a **bare
identity-holder class** is the right home for an **open set discovered from data**.
That asymmetry is the design rule: closed vocabulary → Selection; open discovered
set → referenced-only class.

### Vocabulary as a union's type — `nominee` vs `ceremony`

When one field holds values of **different types** — a nominee can be a human, a
film, a song — that field is a **tagged union**, not a class to subclass. Its type
*is* a vocabulary: the **tag set** of alternatives, with a discriminator (`P31`)
picking which one a given value is — `nominee : ⟨discriminator = P31, tags =
{human, film, song, …}⟩`. The tags are independent general classes (Human, Film)
sharing no common essence, so `HumanNominee`/`FilmNominee` were never subclasses of
a `Nominee` — they're union members. **Union the field; don't subclass the target.**

The *shape of a reference* picks the construct:

- **heterogeneous** field (mixed value-types) → a **union**; its type is a tag-set
  vocabulary. `nominee`/`forWork` — thin *selections over general entities*, holding
  nothing domain-specific but the relation membership; the type-dependent data
  (occupation, genre) belongs to whichever general class each value is.
- **homogeneous** field (one type, many instances) → a **value-domain** vocabulary
  bounds it. `category`.
- **single-typed** field pointing at a **domain-owned** entity with its own data →
  a real **class**. `ceremony` — a `Ceremony` that carries its own `year`.

So `Nominee`/`ForWork` are selections/unions, not classes; `Ceremony` is a class.
This is the domain boundary: the domain owns `Nomination` + the relations +
`Ceremony`; `Human`/`Film`/`Song` are a **general entity layer** the domain selects
into (see [[domain-library-extends]]).

### How a vocabulary is built

One mechanism, read either direction — **pin one end of a relation, collect the
other**:

- **pin the object** (a type), collect **subjects** → instances-of: `?x wdt:P31
  wd:<type>`. How a homogeneous allowed-values set like `OscarCategories` is derived (the
  category type → its instances); also what Override-QID / `SampleClassQuery` do.
- **pin the subjects** (a population), collect **objects** → the distinct values a
  property takes: `NomineeTypes` = the `P31` objects of the nominees, `WorkGenres`
  = the `P136` objects of the works.

And two roles it serves, distinguished by whether it *bounds* or *reports*:

- **constraint** — authored/authoritative, *filters* the load (`OscarCategories` as
  a statement's allowed objects). Set by hand; never overwritten by data.
- **descriptive** — *reports* what a field took, for faceting/subclassing
  (`NomineeTypes`, `WorkGenres`). Derived FROM the data.

The clean way to build a **descriptive** vocabulary is as a **by-product of loading
the field**, not a separate query: declare an entity property-field on a referenced
class (e.g. `Nominee.type = P31`, outgoing, COLLECTION) with its **target set to a
vocabulary name**, and `ReferentFieldLoad` auto-creates that `VocabularySelection`
and refreshes it to the distinct values it loaded — **exhaustive** (every value, not
a sample) and **free** (the load already fetched them). *Declaring the field IS the
discovery.* (`ValueVocabularyDiscovery` — the pin-the-subjects-via-SPARQL form —
remains for *previewing* a vocabulary ahead of loading, or authoring a constraint
from data; the Selections viewer's "Discover vocab" row exposes it.)

**Pending (settled, parked) — the member-class layer.** A flat union of per-type
fields on one class *clashes* on field names (Human's `date` vs Film's `date`); the
resolution is to make the union's members real general classes and **stamp a referent
by its ACTUAL `P31` type** (Human/Film), not the generic union name — so each renders
under its own short-named schema, no prefixes. That's `ReferentClassStamp` stamping
the member class instead of `Nominee`. Deferred until ceremony + type/genre are wired.

---

## Identity at a glance

| Construct | Identity regime | Keyed by | Fields are |
|---|---|---|---|
| Entity class (typed / relation / seeded) | QID | the Wikidata QID | attributes |
| Referenced-only class (`Nominee`, `ForWork`) | QID | the Wikidata QID | additive enrichment |
| Statement class (`Nomination`) | content | natural key over `keyFields` (`CanonicalSpec.DERIVED`) | *are* the identity |
| Selection (VOCABULARY / POPULATION) | — (not served) | the set's membership | n/a |

Membership is **declared** for typed/relation/seeded classes and for the value
domain of a Selection; it is **derived** for referenced-only classes (the
referencing field's property range) and for statement subjects when there is no
subject population (the property read incoming from the allowed objects).

---

## Discovering field properties

You never guess PIDs — you **read them off a representative entity**. To decide
`Nominee.type = P31` and `ForWork.genre = P136`, look up a sample nominee (a
person → `P31 = human`, plus `P106` occupation, `P569` birth date…) and a sample
work (a film → `P31 = film`, `P136` genre, `P57` director, `P161` cast…). The
property tells you the field.

The explorer tabs in the modelbuilder (`ModelSourceWorkbenchPanel`) do this:

- **Discover** (`PropertyDiscoveryPanel`) — samples N instances of the selected
  class and profiles which properties they carry (outgoing) or reference them
  (incoming), ranked by coverage; click a property to add it as a field with the
  PID pre-filled.
- **Explore** (`ExploreByExamplePanel`) — "explore by example": name a thing, pick
  the entity, run a relation battery to surface candidate *sets* it anchors; add
  as Seed QIDs or use as the membership type.
- **Sample** (`NodeSamplePanel`) — sample a field to detect its cardinality
  (Single vs Collection).
- **Properties** (`CachedPropertyViewablePanel`) — the cached property catalogue;
  select one to configure a field.
- **Example-first statement view** (`StatementSummaryPanel`, GitHub #91) — push in
  a few sample QIDs and see the merged coverage of *property → qualifiers*
  (badged by how many samples carry each) as clickable rows; a `+` configures the
  field / qualifier field via the Add-Field path. A **Values** tab shows the
  actual value labels so you can see what you'd be capturing.

**Referenced-only classes and discovery.** A referenced class's declared
entity property-fields **do load** onto its referents on regenerate — declaring
`Nominee.type (P31)` / `ForWork.genre (P136)` is the whole configuration
(`ReferentFieldLoad`, run after `ReferentClassStamp`; outgoing entity claims via
`wbgetentities`). What is still *pending* is discovery *convenience*: these tabs
sample from a class's *membership*, and a referenced-only class has none, so today
you feed the inspector a sample QID by hand rather than have it auto-sample a
`Nominee` you already generated.

---

## Worked example — the Oscars domain

- **`Nomination`** — a *Statement class*: reifies `P1411` statements into records;
  content identity (natural key over nominee + category + year); fields from the
  value and qualifiers (`nominee ← P2453`, `forWork ← P1686`, `year ← P585`,
  `won` companion). Allowed objects = the `OscarCategories` Selection.
- **`OscarCategories`** — a *VOCABULARY Selection*: the 59 category QIDs. It is the
  `category` field's type *and* the statement's allowed objects.
- **`Nominee` / `ForWork`** — *referenced-only entity classes* (identity holders):
  QID identity, membership derived from `Nomination.nominee (P2453)` /
  `Nomination.forWork (P1686)`. Bare today; `type = P31` / `genre = P136` are the
  natural first fields to add, discovered by sampling a person / a film.
