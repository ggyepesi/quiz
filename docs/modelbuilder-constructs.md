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

## Class (`GeneratedClassModel`)

A class is a named kind of thing plus the fields you load on it. Its membership
"shape" is classified by `MembershipPattern` and shown on the class-tree node.

### Entity classes — QID identity ("identity holders")

An entity class **is** a set of Wikidata entities, so its identity is the **QID**:
one instance per QID, and every reference to the same entity resolves to that one
object (`GeneratedQuizableMapper` unifies by QID — see
[[canonicalization-model.md]]). This unification is the whole point: the same film
appearing in five nominations is **one** node you can pivot on.

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
  category error; the honest label names the deriving field, e.g.
  *"Derived from forWork (P1686)"*. *(Label wording is a pending display fix; the
  underlying model is already correct.)*
- Even fieldless, it is **load-bearing**: it is the **join key** for the inverted
  views — "all nominations of a nominee", "every nomination for a work" are
  inverts *over the shared QID identity*. No identity holder, nothing to group by.
- **Fields on it** (e.g. `genre` on `ForWork`) are not membership — they are
  *properties of the entities in the derived set*, loaded per-referent. See
  [Discovering field properties](#discovering-field-properties) below.

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

`StatementClassSource` carries:

- the **statement property** (`propertyPid`, e.g. `P1411`) — its identity as a
  statement class keys on this, not on a source class;
- an **optional source class** — the already-extracted members whose statements
  are loaded. Blank ⇒ **subjects are discovered directly** from the property
  (`PopulationSubjectLoader`, guarded by a value domain);
- an **optional value domain** — a VOCABULARY Selection whose values are the
  allowed statement values (see below), replacing a filter that would otherwise be
  re-specified.

Its fields come from the statement's **value** and its **qualifiers** (e.g.
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

Two kinds (today one class + a `Kind` enum; the natural refactor is two
subclasses, **`VocabularySelection`** / **`PopulationSelection`**, so each carries
only its own fields and illegal combinations become unrepresentable):

- **`VOCABULARY`** — a **value domain**: the allowed values of a field (explicit
  QIDs and/or a `P31` type filter). Example: `OscarCategories` = the 59 category
  QIDs. A referent field can point at it as its *type* (e.g. `category →
  OscarCategories`), and a reify reads it as its value filter — one vocabulary,
  referenced in both roles, never duplicated.
- **`POPULATION`** — a **subject set**: the entities matching a membership relation
  (`relationPid` into `targetQids`), for a reify to draw its subjects from — e.g.
  "the entities with `P1411` into the Oscar categories" (the nominee subjects).

A Selection is the right home for a **closed, known vocabulary**; a **bare
identity-holder class** is the right home for an **open set discovered from data**.
That asymmetry is the design rule: closed vocabulary → Selection; open discovered
set → referenced-only class.

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
referencing field's property range) and for reify subjects when there is no source
class (the statement property into the value domain).

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
- **Properties** (`CachedPropertyQuizablePanel`) — the cached property catalogue;
  select one to configure a field.
- **Example-first statement view** (`StatementSummaryPanel`, GitHub #91) — push in
  a few sample QIDs and see the merged coverage of *property → qualifiers*
  (badged by how many samples carry each) as clickable rows; a `+` configures the
  field / qualifier field via the Add-Field path. A **Values** tab shows the
  actual value labels so you can see what you'd be capturing.

**Referenced-only classes and discovery.** These tabs sample from a class's
*membership*, and a referenced-only class has none — so today you feed the
inspector a sample QID by hand rather than have it auto-sample a `Nominee` you
already generated. Two enablers close that loop (pending): sample a referenced
class from **its own generated referents**, and **load its declared fields'
properties onto its referents** (generalizing the current hardcoded `P31 → type`
hook in `QualifierLoader`) so a declared `type`/`genre` actually populates on
regenerate.

---

## Worked example — the Oscars domain

- **`Nomination`** — a *Statement class*: reifies `P1411` statements into records;
  content identity (natural key over nominee + category + year); fields from the
  value and qualifiers (`nominee ← P2453`, `forWork ← P1686`, `year ← P585`,
  `won` companion). Value domain = the `OscarCategories` Selection.
- **`OscarCategories`** — a *VOCABULARY Selection*: the 59 category QIDs. It is the
  `category` field's type *and* the reify's value filter.
- **`Nominee` / `ForWork`** — *referenced-only entity classes* (identity holders):
  QID identity, membership derived from `Nomination.nominee (P2453)` /
  `Nomination.forWork (P1686)`. Bare today; `type = P31` / `genre = P136` are the
  natural first fields to add, discovered by sampling a person / a film.
