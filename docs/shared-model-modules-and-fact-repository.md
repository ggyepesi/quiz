# Shared model modules and an incremental fact repository

## Status

Design proposal, reviewed against the repository on 2026-08-31. The first proving domain
is Nobel Prizes; Oscars and History are the first intended consumers after that.

Findings from that review are folded in where they apply: `Person` has already diverged
across three domains (Milestone 3), declaration-level coverage already exists and needs
re-keying rather than building (Layer 2, Milestone 5), and deciding what a reference IS
gates everything else (Milestone 0).

The repository key is a ⟨namespace, entity, **selector**⟩, not a Wikidata property — most
of what the pipeline already acquires is not property-shaped. A pin is only reproducible
against an immutable version, so an import records a content digest. And a domain
subtracts acquisition by declaring module fields optional, never by deriving a class:
inheritance adds structure and cannot remove a field's demand.

## Motivation

`Person` and its owned `Name` structure are useful in several domains. Copying their
configuration gives each domain an immediately divergent definition, while sharing a
generated snapshot would mix domain-specific membership, presentation and lifecycle.

The reusable parts are two different things:

1. **Schema:** what `Person` and `Name` mean and how their fields are acquired.
2. **Evidence:** source facts already acquired for a source entity.

They must remain separate. A shared model module owns schema. An incremental fact
repository owns acquired evidence. Each domain remains responsible for projecting that
evidence into its own snapshot.

## Proposed shape

```text
Shared model module: people
├── Name (owned)
│   ├── givenName
│   └── familyName
└── Person
    ├── structuredName → Name
    ├── portrait
    ├── birthDate
    ├── deathDate
    ├── nationality → Country
    ├── spouse → Person
    └── children → List<Person>

Nobel domain
├── imports people@version
├── Nobel-specific classes and statement/aggregate configuration
└── local presentation/acquisition choices for imported fields
```

An import is not a copy. Imported declarations retain stable identities such as
`shared.people.Person`, independent of their display names and of the importing domain.

## Layer 1: shared model modules

A module is a versioned collection of class declarations and their dependencies. It may
contain more than one class because useful constructs often form a unit: `Person`
depends on the owned `Name` class, and may later depend on a shared `Country` construct.

### Import behavior

- A domain declares imports explicitly.
- Imported classes appear normally in ModelBuilder, visibly marked as imported.
- Their structural declarations are read-only in the importing domain.
- A domain may apply a limited, explicit local overlay.
- Saving a domain records the module identity and version, not a private copy of it.
- Generation resolves and compiles the domain plus its imports as one model.
- Missing modules or incompatible versions are validation errors, never silent
  degradation.

### Local overlays

The initial overlay should be deliberately narrow, and it is two different things:

**Presentation overlay** — inert with respect to acquisition:

- served or hidden;
- search, sort and view defaults;
- display-name selection.

**Acquisition overlay** — whether an expensive optional field is acquired for this
domain. This one is not presentation: it changes the domain's fact demand, and therefore
what the repository is asked for and what "covered" means for a shared source entity. It
is listed separately so it is not slipped in as a display preference.

For the first slice, take one of two positions: acquire every field the module declares,
or let the module mark selected fields explicitly optional and let a domain opt in. A
derived class is NOT the way to subtract — inheritance adds structure, it does not remove
an inherited field or the demand that field creates.

An overlay must not silently redefine identity, field type, cardinality, property
mapping or ownership. A domain that needs a structural difference should declare an
explicit derived class. This keeps the shared declaration authoritative and makes the
difference visible.

### Stable references and versions

Class and field references should use stable declaration identities internally rather
than names. Names remain editable presentation. A saved domain pins a module version so
regeneration remains reproducible. Updating an import is an explicit action with a
preview of affected classes, fields and generated data.

**This sentence is the largest piece of work in the proposal, not an internal detail.**
Names are the reference mechanism today. `GeneratedProjectModel.renameClass` rewrites six
kinds of reference by name — field targets (recursively), base classes, statement source
classes, aggregate source classes, entity-kind rules and role-selection owners — and
`entityClassName` resolves to a class OR a selection in one shared namespace, where the
class wins. Aggregate sources joined that list this month, which is the point: the set of
places a name is a reference grows with the model vocabulary, and every addition is a
place an import has to be resolved. Several defects this month came
from exactly that arrangement.

Pinning also has to mean something. `people@1` is reproducible only if version 1 can
never be overwritten and stays available, so a saved import records four things:

- module id;
- declared version;
- content digest of the resolved declarations;
- the resolved declaration identities themselves.

A declaration identity is stable ACROSS versions — `shared.people.Person` is the same
declaration in `people@1` and `people@2`. The version says which definition of it a
domain resolved. The digest is what makes a pin verifiable rather than trusted.

It also cannot be deferred quietly: `shared.people.Person` imported into a domain that
already has a local `Person`, or a vocabulary of that name, is precisely the collision the
namespace guards were written for. Either it becomes Milestone 0 below, or the first slice
states plainly that references stay name-based and imports therefore cannot shadow a local
declaration.

## Layer 2: incremental source-fact repository

The repository stores reusable source evidence, not generated domain instances. Its
conceptual key is:

```text
⟨source namespace, source entity, fact selector⟩
```

For a Wikidata person it may retain:

```text
Wikidata · QID
├── label and aliases
├── P18  portrait
├── P569 birth date
├── P570 death date
├── P27  nationality
├── P26  spouse
└── P40  children
```

Each retained fact carries acquisition coverage and provenance: what was requested,
whether the source answered, retrieval/version information, and whether the result was
empty. An empty answer is therefore reusable and is not requested forever.

**A property is one kind of selector, not the key.** A Wikidata property covers P18 and
P569 well and covers little else the pipeline already acquires: labels and aliases are
per language, sitelinks are per site, and Wikipedia category memberships, infobox
parameters, statement qualifiers and retrieved documents are none of those. A snapshot
entity today carries `aliases`, `wikipediaCategories`, `wikipediaInfoboxTemplate`,
`wikipediaInfoboxParameters` and their source documents alongside its claims, so a
property-shaped key would leave most of that uncacheable.

The selector is therefore provider-owned and opaque to the repository: a property, a
metadata kind, a language, a category membership, an infobox parameter. The repository
stores and compares selectors; only the provider constructs and interprets them.

The repository is datasource-neutral at its boundary. Provider adapters translate
their native identifiers, properties, documents and version markers into the common
fact contract.

### Incremental behavior

Before acquisition, the compiled model produces exact fact demands. The repository
answers covered demands and returns only the missing or stale remainder to the provider.
The new response is banked before the domain projects it.

Consequently:

- Nobel can acquire portrait and dates for a laureate;
- Oscars can later reuse those facts for the same source identity;
- only newly declared or stale facts require network work;
- each domain still builds and saves its own deterministic snapshot.

Declaration-level coverage already exists and is already persisted. Every snapshot
carries `LoadedDeclaration`:

```java
record LoadedDeclaration(String className, String fieldName, String propertyPid,
                         int covered, List<String> coveredQids)
```

It records exactly which QIDs a declaration covered, so a later enrich asks only for what
is new rather than re-asking for every entity the source had no answer for — the
empty-answer banking of acceptance criterion 5, working today.

Two things separate it from this repository, and naming them keeps the remaining work
honest:

1. **Scope.** It survives later runs that continue from the same domain snapshot, and
   already stops those runs refetching what a previous Enrich covered. What it cannot do
   is serve an independently generated snapshot, or another domain.
2. **Key.** It is keyed by ⟨class, field, property⟩ — DOMAIN coordinates. The repository
   needs ⟨source namespace, source entity, fact selector⟩ — SOURCE coordinates. That
   re-keying is what makes one acquired fact reusable by a domain that calls the class
   something else, and it is the substance of Layer 2.

What remains genuinely new is durable storage, a freshness/version policy, and the
provider translation at the boundary.

## Layer 3: domain materialization

A domain snapshot remains the product consumed by TransformApp and the web application.
It contains instances of the domain's compiled classes and records the evidence used to
produce them. It does not become the shared cache for another domain.

The same source entity may therefore materialize into different domain views without
duplicating acquisition:

```text
shared source facts
       │
       ├── Nobel projection  → Nobel domain Person
       ├── Oscars projection → Oscars domain Person
       └── History projection→ History domain Person
```

## References are not population expansion

Spouse and children make this distinction load-bearing:

```text
fact acquired ≠ referenced entity expanded ≠ served domain member
```

Reading `P26` or `P40` may create a lightweight reference carrying source identity and
label. It must not automatically make that person a fully enriched or served member.
Following the relation is a separate, explicit graph-expansion policy with bounds and
visible coverage. This prevents a small laureate population from silently growing into
a large family graph.

The default policy for Nobel should be:

- acquire spouse/children references for existing laureates;
- resolve their labels and source links;
- do not recursively acquire their Person fields;
- do not serve them as laureates or top-level People;
- allow a later explicit graph policy to expand selected relations.

## Imported classes in local identity

A local statement or aggregate class will reference an imported one, and the answer is
less alarming than it first looks.

`Laureate` stays LOCAL. The module contains `Person` and `Name`; Nobel's `Laureate` is the
domain's own participation class — it holds the 33 organisations that are not people —
and it should extend or classify as the imported `Person` rather than be replaced by it.

A canonical or aggregate key containing a Person-valued field is rendered by that
INSTANCE's source identity, not by anything about its class: `StableIdentity` reads a
reference's identifier, so the key component is the QID. A record's identity therefore
does not span a version pin merely because the class it references was re-versioned.

What DOES threaten record identity is a module version that changes identity DERIVATION —
a different canonical key, a changed ownership regime, a field that stops being
single-valued. So an import update must report identity impact when it changes those, and
need not when it only adds or renames a field.

## Nobel proving sequence

1. Reconcile the three existing `Person` declarations and extract `Name` and the agreed
   `Person` into a `people` module (see Milestone 3 — `Name` is identical everywhere,
   `Person` is not).
2. Import that module into Nobel without changing generated counts.
3. Add `portrait`, `birthDate` and `deathDate`. These are bounded scalar facts and
   provide the safest end-to-end test.
4. Add `nationality`, settling whether `Country` is a shared entity class or a bounded
   vocabulary before reuse spreads.
5. Add `spouse` and `children` as non-expanding references.
6. Import the same module into Oscars and History and compare their existing output.
7. Add durable repository-backed coverage so repeated enrichment fetches only missing
   facts.

## Implementation milestones

### Milestone 0 — stable declaration identities (implemented)

References use persisted declaration identities. Names remain editable, readable hints
and the current runtime type keys; they are no longer the authority used to resolve a
model reference. Old models acquire deterministic identities on load, so merely opening
and saving a model does not invent a different identity on every run.

The migration covers class inheritance, entity-valued fields, statement source classes
and value selections, aggregate source classes, entity-kind rules, role-selection
owners, source-binding owners and the project root. The compiler resolves by identity
first and refreshes the readable name hints. Duplicate declaration identities are a
validation error.

Classes and selections retain their existing shared, unique-name UI namespace for this
slice. Stable identity therefore does not introduce shadowing: the first module import
replaces an adopted same-name local declaration rather than allowing both to coexist.
That keeps current runtime type stamps unambiguous while removing names from durable
reference semantics. If module composition later needs two visible declarations with
the same name, that requires an explicit qualified-name/runtime-type-key design rather
than an accidental relaxation of the existing namespace guard.

### Milestone 1 — module format and compiler

- Define module identity, version and declaration identities.
- Persist domain import declarations.
- Resolve imports before validation and compilation.
- Reject missing, cyclic or incompatible imports.
- Add forcing tests showing imported and locally authored classes compile identically.

### Milestone 2 — ModelBuilder imports

- Show imported classes and their origin visibly.
- Make structural controls read-only.
- Provide the explicit local overlay controls.
- Add an import/update/remove workflow with an impact preview.
- Ensure copying remains a separate migration action, not import semantics.

### Milestone 3 — reconcile and adopt `people`

There is no single existing declaration to move. Three domains already declare these
classes and have diverged, which is the proposal's own motivation observed in the repo:

| domain | `Person` | `Name` |
|---|---|---|
| History | 10 fields: `structuredName`, `birthName` P1477, `nativeName` P1559, `pseudonyms` P742, `dateOfBirth` P569, **`offices`**, `dateOfDeath` P570, `type` P31, `spouse` P26, `image` P18 | `givenName` P735, `familyName` P734 |
| Oscars | 6 fields: the same first five, then **`deathDate`** P570 | identical |
| Nobel | 0 fields; identity/label/aliases bindings and a `Q5` entity-kind rule | — |

`Name` is identical in both — same fields, types, cardinalities and property mappings —
and extracts unchanged; the only difference in the saved JSON is unset values written as
`""` in one domain and omitted in the other. `Person` needs three decisions before
anything moves:

- **Which fields are shared.** The five common ones are uncontroversial; `type`, `spouse`
  and `image` exist only in History and may belong in the module or in that domain.
- **What happens to `offices`.** It is a History-specific back-reference into that
  domain's own classes and must NOT enter a shared module — the first real test of the
  rule that a domain needing a structural difference declares a derived class.
- **Which name wins for P570.** `dateOfDeath` or `deathDate`. Trivial to decide and
  impossible to leave undecided, since one domain's saved data uses each.

Then: migrate Nobel first, since it declares no `Person` fields at all and so cannot
regress; prove Oscars generates equivalently; migrate History last, because it carries the
fields the module will not take.

History's migration has one decision the others do not. If `HistoricalPerson` extends
`shared.people.Person`, existing fields and entity-kind rules that target `Person` do not
begin producing `HistoricalPerson` on their own — they name `Person` and will keep
naming it. The milestone has to say, field by field and population by population, which
target the shared base and which receive the local subtype. Getting this wrong is silent:
the model still compiles and the subtype simply stays empty.

### Milestone 4 — enrich Person

- Add portrait and life dates.
- Decide and add nationality representation.
- Add spouse and children with the non-expanding-reference policy.
- Surface source, coverage and provenance in generation explanations and logs.

### Milestone 5 — promote coverage into a durable repository

Coverage is not a new mechanism here; `LoadedDeclaration` already banks it per snapshot,
exact QIDs and empty answers included. This milestone re-keys and outlives it.

- Re-key coverage from ⟨class, field, property⟩ to ⟨source namespace, source entity,
  fact selector⟩.
  This is the substantive change: it is what lets a fact acquired for one domain answer
  another domain's demand for the same source entity.
- Define the datasource-neutral fact and coverage records at that key.
- Reuse the existing demand-plan and provider-operation architecture.
- Add persistent lookup, freshness/version policy and bounded storage.
- Keep banking empty answers, which already works and must not be lost in the move.
- Report repository hits, remote misses, stale refreshes and bytes avoided.
- Keep snapshot acceptance transactional: a failed generation does not publish a new
  domain result merely because some facts were banked.

### Milestone 6 — cross-domain proof

- Enrich a Person shared by Nobel and Oscars.
- Verify the second domain performs no remote request for already-covered facts.
- Verify each domain retains its own presentation, served membership and snapshot.
- Verify changing the shared module produces an explicit import-update impact rather
  than silently changing a saved domain.

## Acceptance criteria

The design is successful when:

1. `Person` and `Name` have one structural declaration used by at least Nobel and
   Oscars.
2. Imported declarations cannot be accidentally edited as local classes.
3. A domain remains reproducible against its pinned module version.
4. Acquiring a fact once can satisfy the same source demand in another domain or run.
5. Empty source answers are remembered — already true per snapshot, and the criterion is
   that it survives the re-keying rather than that it starts working.
6. Spouse and children do not enlarge served membership without an explicit expansion
   action.
7. Removing or updating an import reports its impact before modifying the model.
8. Generated snapshots remain self-contained products even when acquisition reused the
   repository.

## Non-goals for the first slice

- A universal, merged Person instance shared directly by every domain.
- Arbitrary per-domain structural edits to imported classes.
- Automatic recursive family-tree generation.
- A public module marketplace or remote dependency resolver.
- Replacing snapshots with a live repository-backed view.

The first slice should prove that shared schema eliminates configuration duplication.
The durable repository follows once that schema gives fact demand a stable meaning.
