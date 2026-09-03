# Candidate production and keyed reduction

## Status

Design for the next ModelBuilder refactor. This document separates three decisions
that are currently entangled in class constructs and in the Wikidata reification
path:

1. how a datasource produces candidate instances;
2. which candidates denote the same modeled instance;
3. how the values of those candidates are combined.

The design deliberately provides no compatibility layer for generated snapshots.
Affected domains are regenerated after their declarations are corrected.

## Forcing examples

### Nobel prizes

Wikidata exposes one P166 statement from each laureate. Several statements can denote
one modeled achievement, identified by the configured tuple `category + year +
motivation`. Grouping by that key must union the laureates while requiring category,
year and motivation to agree.

This is not a special property of a Statement class. It is ordinary keyed reduction
over candidates that happen to have been produced from Wikidata statements.

### One entity through several sources

Wikidata, Wikipedia and DBpedia may each contribute values to the same modeled
instance. Their acquisition and mapping differ, but once they have produced typed
field values the rules for identity, conflict handling, provenance and presentation
must not be reimplemented by each provider.

### An entity class with a modeled key

Source identity is a useful default, not a law of an entity construct. A class may use
a provider-qualified source identity as its key, or deliberately configure a content
key. Entity and Statement therefore describe candidate *production shapes*; they do
not select different canonicalization engines.

## Boundary of this refactor

The boundary is drawn **after source-specific normalization**. Acquisition, parsing and
the consolidation of duplicate source representations remain inside each datasource.
The common refactor begins only when a provider has produced normalized candidates.

```text
┌──────────────── datasource-dependent ────────────────┐
│ acquire source material                              │
│ parse source-native structures                       │
│ normalize source-specific representations            │
│                                                      │
│ Wikidata owns entities, statements, qualifiers,      │
│ mirrored projections and source-occurrence identity  │
└──────────────────────────┬───────────────────────────┘
                           │ normalized candidates
                           │ typed values + source identity
                           │ + occurrence identity + provenance
┌──────────────────────────▼───────────────────────────┐
│ datasource-independent                               │
│ configured key → partition → per-field reduction     │
│ → canonical instance → references/display/serving    │
└──────────────────────────────────────────────────────┘
```

Wikidata already contains normalization knowledge, including its handling of statements
reached through more than one projection. That implementation stays in place initially.
This design does not introduce a generic normalization framework.

If a second datasource later exhibits the same concrete problem, compare its solution
with Wikidata's. Factor out only mechanics that are genuinely shared. Until then,
normalization is a provider responsibility and the only shared construct is its output
contract.

## The common pipeline

```text
normalized candidates from a datasource
      |
      v
compute the configured key for every candidate
      |
      v
partition candidates by <class, key>
      |
      v
reduce every field with its configured rule
      |
      v
materialize one canonical instance per partition
      |
      v
resolve references, inverses, presentation and serving
```

The map/reduce wording is semantic, not a commitment to a distributed framework.
The first implementation can remain an in-process transformation over the generated
pool.

## Four independent axes

### 1. Normalized candidate production

The class construct says how normalized candidate rows arise:

- **Source** maps a source entity or document.
- **Statement** maps a statement tuple: configured subject field, fixed property,
  configured object field, and additional qualifier fields.
- **Owned** maps one component at an owning production site.
- **Aggregate** maps a configured group of already materialized instances into a new,
  coarser-grained class.

Constructs and providers may impose structural requirements on production and perform
source-specific normalization, but neither owns the generic key or field-reduction
semantics.

### 2. Key

A key is an ordered list of explicit components. A component is either:

- a configured field path; or
- a named structural identity supplied by production, such as
  **source identity** or **owner + production site**.

Source identity is provider-qualified (`namespace + id`), so identifiers from two
datasources cannot collide accidentally. A Statement's subject and object are ordinary
fields and become key components only when selected by the modeller. No editor,
compiler or execution path derives a key from field roles.

An empty key is an explicit validation error for a generatable class unless its
construct supplies a mandatory structural key (Owned is the first such case). It must
never mean “guess a key from the current fields”.

### 3. Field reduction

Each non-key field declares what happens when a key partition contains several
candidates. The initial vocabulary should be small and deterministic:

- **Require agreement** — all non-empty values must be semantically equal; otherwise
  report a conflict.
- **Union distinct** — flatten collections and retain distinct values in stable order.
- **Prefer non-empty** — use the one non-empty value; conflicting non-empty values are
  reported rather than silently ranked.
- **Choose by policy** — only when a separate, explicit ordering or evidence policy is
  configured. “First encountered” is not a policy.

Key components are not reduced: their normalized values created the partition. Missing
key policy (reject candidate, form an incomplete group, or fail) is configured beside
the key, not hidden inside a field reducer.

Every accepted value retains its evidence lineage. A reducer combines values and their
provenance; it never turns several source assertions into an unexplained value.

### 4. Presentation

Display name, view configuration and serving are downstream of canonicalization. They
may read the canonical fields but cannot affect grouping or reduction.

## Collision reduction is not aggregation

The two operations can share stable-key and reducer machinery, but they answer different
questions:

- **collision reduction** says that several candidates are copies or fragments of one
  instance of the *same class*;
- **aggregation** creates an instance of a *different class* at a deliberately coarser
  grain and normally retains the source instances as members.

For Nobel, reducing repeated `LaureatesWithMotivation` candidates can union laureates
that share one configured key. Creating a `NobelPrize` grouped by category and year is
still aggregation because it produces another class.

`AggregateClassSource` should eventually compile to the same neutral key and reducer
vocabulary, while remaining an explicit construction step.

## Datasource contract

A datasource provider owns:

- operations and configuration needed to acquire source material;
- source-native identity and references;
- mapping source values to the neutral datatypes;
- source documents and evidence lineage;
- candidate production for the constructs it supports;
- normalization of source-specific duplicate or partial representations.

It does not own canonical keys, duplicate policy, field conflict handling, display-name
composition or serving. Those are model semantics shared by every provider.

Normalization may consolidate several acquisition paths to the same source assertion,
but it must not combine distinct source assertions merely because a later modeled key
might group them. In particular:

- mirrored views of one Wikidata statement may become one normalized candidate carrying
  every origin;
- three distinct P166 statements for three Nobel laureates remain three candidates;
  their configured model key and reducers may combine them later.

The decision must use source-native evidence such as provider, document/entity,
statement GUID or occurrence identity. It must not use the model's canonical key.

A neutral candidate needs, at minimum:

- target class declaration identity;
- zero or more provider-qualified source identities;
- the production-site identity when the construct supplies one;
- typed field values, each with provenance;
- a source occurrence identity for diagnostics, never confused with the final modeled
  identity.

This extends the existing datasource binding/evidence work; it must not introduce a
parallel provider registry or a second stable-value encoding.

## Compilation and execution ownership

The editable model is the only authored source of truth. Compilation validates and
resolves it once into a neutral canonicalization plan containing:

- resolved key components;
- missing-key policy;
- one resolved reducer per field;
- construct-specific production metadata.

Generation, sampling, remap and enrich consume that compiled plan. They do not rescan
editable fields or choose defaults independently.

Enrichment that changes a key component invalidates the old partition and requires a
new canonicalization pass. Enrichment of non-key fields may reduce into the existing
partition. This distinction must be visible in the run explanation and log.

## Configuration UI

Every class construct should contain the same two downstream sections after its
production-specific section:

1. **Identity** — key components, in order, with source/owner identity offered as named
   structural components where available;
2. **When the same key occurs** — a field-by-field table showing the reducer and the
   result expected for two or more candidates.

The editor should preview a small collision group using sampled candidates: key, source
occurrences, chosen/combined values, conflicts and resulting instance. Changing a
selection only changes the preview; applying configuration remains an explicit action.

The current class-wide “Keep one / Merge records” control is transitional. It cannot
express “category must agree, laureates union, motivation prefer non-empty”, and its
hard-coded behavior must not become the common engine.

## Review of the current configuration work

The recent statement changes establish useful foundations:

- subject and object are explicit destinations in one visible triple;
- both ends use `EntityBound` and the same `EntityEndEditor`;
- a vocabulary bound is a live declaration reference rather than a copied QID list;
- the object bound no longer overloads the class's membership QID;
- identity checkboxes and collision reporting make the grain inspectable;
- statement sampling now renders where it is invoked and exposes validation failures.

The remaining seams are important because this refactor would otherwise preserve them:

1. `StatementIdentity.seedIfEmpty` still derives subject/object keys, and is called from
   four editing paths. That contradicts the rule that the key is entirely configured.
2. A subject `VOCABULARY` bound reaches `PopulationSubjectLoader.buildQuery` unresolved;
   the switch emits no subject restriction for it. A visible configured bound can
   therefore execute as unbounded.
3. Object-bound compilation in `ModelStatementReifications` reduces `RELATION` to the
   first P31 target and later reconstructs an `instancesOf` bound. General relation,
   multiple targets and descendant semantics are lost.
4. `ClassKind.usesCanonicalKey()` admits only Statement, while `Canonicalizer` always
   takes source identity for Source. The current model cannot express the proposed
   entity-class key.
5. `DuplicatePolicy` is class-wide. `mergePartialRecord` hard-codes union for collections,
   fill-empty for scalars and silent preference for conflicting scalars.
6. `ModelAggregates` owns another grouping loop and its own output construction. It uses
   the shared `StableIdentity`, which is good, but not a shared grouping/reduction plan.
7. Reification still has statement-specific survivor selection (`primaryListField` and
   work-anchored preference). These remain on the Wikidata side of the boundary for the
   first refactor. They need only expose an auditable normalized candidate; they must
   not leak into the neutral reducer as implicit precedence.
8. `ModelStatementReifications` still exposes editable-model entry points that compile
   internally as well as compiled-model entry points. The final execution path should
   accept only the already compiled declaration.

## Review comments

### "No key" names three different intents, and the framing picks the wrong one

This is the sharpest gap. Keyed reduction makes the LITERAL reading of an empty key
"group by nothing", which is one partition — a whole class collapsing into a single
instance. That is never what anyone wants, and it is what the pipeline in this document
describes if read as written.

The current code means the opposite, and only by accident of where a guard sits:

```java
keyOf(candidate, /* no fields */)  ->  ""      // identical for every candidate
if (!c.dedupBy().isEmpty()) { ...reduce... }   // the ONLY thing preventing collapse
```

`TransformEngine.keyOf` over an empty list returns the same empty string for everything,
so the reducer would collapse the class. What produces "every candidate stands alone" is
the conditional OUTSIDE the reducer that skips it. Delete that guard — a plausible tidy —
and every statement class silently becomes one instance. A configuration whose meaning
lives in a guard rather than in the configuration is exactly the shape this refactor is
meant to remove.

Three intents hide under "no key":

| intent | what it means | how it should be said |
|---|---|---|
| one instance per source occurrence | each candidate stands alone | a key ON the occurrence identity |
| one instance per class | every candidate is the same thing | a key of `()` — never wanted, but expressible |
| not decided yet | the modeller has not chosen | a validation error |

So the resolution is: **there is no such thing as "no key".** What is called surrogate
identity IS a key — on the candidate's own occurrence identity, which is distinct by
construction. That makes it a third named structural component beside `SOURCE_IDENTITY`
and `OWNER_SITE_IDENTITY`, and it makes this document's "an empty key is a validation
error" rule land cleanly: an empty key is an error precisely because every real intent
now has a name, so blankness can only mean "not decided".

One line above needs adjusting to allow it. The candidate contract says occurrence
identity is "for diagnostics, never confused with the final modeled identity". Choosing
it as an explicit key component is not that confusion — it is the modeller saying one
instance per source occurrence. The warning should be against INFERRING it, not against
naming it.

### A collection-valued key component has no single value to key on

"Key components are not reduced: their normalized values created the partition" assumes
each has one normalized value. A collection does not. Nobel is the live case: `laureates`
is a participants collection, and it is excluded from that class's key today.

Either the key compiler rejects a collection component, or the document states what the
partition value of a collection is (the set? its stable encoding? ordered or not?).
Rejecting is the smaller rule and can be relaxed later; leaving it unstated means each
implementation picks, which is how two discovery paths appear.

### "Stable order" needs its source named

Union-distinct promises "distinct values in stable order", and milestone 2 promises
deterministic output. Stable with respect to WHAT? If it is candidate encounter order,
it is only as deterministic as acquisition order — and R18 records that WDQS can answer
a partial result as a silent 200, so encounter order is not reproducible across runs.

A snapshot is meant to be reproducible from its model. That requires the order to come
from something the model fixes — the key's own component order, a declared sort, or the
stable encoding of the values themselves — not from the sequence in which rows arrived.

### Milestone 3's deletion list removes one thing too many

`StatementIdentity` is named for deletion along with `mergePartialRecord` and the
editable-model entry points. Its `seedIfEmpty` should indeed go — that is milestone 0.
But `structuralKey` answers a different question: WHICH FIELDS are the subject and object
destinations. Milestone 6's Identity section needs exactly that to offer them as named
components, and nothing else computes it.

The seeding goes; the question it answers does not.

### On the two P2 findings

They are axes 2 and 3 of this document, not defects to fix first.
`ClassKind.usesCanonicalKey()` admitting only Statement, and `DuplicatePolicy` being
class-wide with `mergePartialRecord` hard-coding union-for-collections and
silent-preference-for-conflicting-scalars, are both accurate — but fixing either
piecemeal now would build the parallel path milestone 7 exists to delete.

## Implementation plan

### Milestone 0 — make the current statement declaration truthful

- Remove all automatic key seeding and require the user to select statement keys.
- Resolve vocabulary bounds for both ends during compilation.
- Preserve the full `EntityBound` through compilation and execution; do not reconstruct
  a narrower P31-only approximation.
- Add forcing tests for subject vocabulary, arbitrary relation, multiple targets and
  descendants.

This milestone is intentionally first: it prevents the generic engine from being built
on configuration that does not mean what it displays.

### Milestone 1 — neutral canonicalization declarations

- Move key/reduction vocabulary to a provider-neutral package.
- Add explicit structural key components (`SOURCE_IDENTITY`, `OWNER_SITE_IDENTITY`) next
  to field-path components.
- Add per-field reducer declarations and missing-key policy.
- Compile them once into a `CanonicalizationPlan`; validate paths and datatype/reducer
  compatibility there.
- Keep current behavior only through explicitly materialized declarations in the shipped
  models, not runtime fallbacks.

### Milestone 2 — one keyed reduction engine

- Introduce one engine that partitions neutral candidates by class and stable key.
- Implement require-agreement, union-distinct and prefer-non-empty with deterministic
  output and provenance retention.
- Report candidate count, partition count, reductions and conflicts before materializing
  instances.
- Pin stable collection/map/date/reference semantics with tests using the existing
  `StableIdentity` owner rather than copying it.

### Milestone 3 — adapt normalized Wikidata output

- Put a narrow adapter after the existing Wikidata entity/statement normalization. It
  emits the neutral candidate contract without redesigning Wikidata acquisition.
- Supply provider-qualified `SOURCE_IDENTITY` for normalized entity candidates.
- Supply tuple fields, source occurrence identity and provenance for normalized
  statement candidates.
- Keep canonical-list/work-anchored behavior inside Wikidata normalization for now, but
  ensure it does not consult the model key or perform model-level field reduction.
- Delete `mergePartialRecord`, `StatementIdentity` and editable-model reification entry
  points once the common reducer and parity tests replace their responsibilities.

### Milestone 4 — unify Aggregate and Owned boundaries

- Compile aggregate grouping keys through the neutral key compiler and express its
  members field as `Union distinct`.
- Keep aggregate output as a distinct class-construction step.
- Confirm Owned's mandatory owner/site key can use the common identity representation;
  do not force owned candidates through content grouping when exactly one candidate per
  site is guaranteed.
- Remove aggregate-local stable-key/grouping code after the common engine covers it.

### Milestone 5 — provider-facing normalized-candidate contract

- Add the normalized-candidate handoff to the existing datasource operation/binding
  architecture.
- Connect Wikidata first without generalizing its normalization implementation.
- Connect Wikipedia/DBpedia paths that contribute modeled values when they can satisfy
  the same output contract.
- Require providers to stop at normalized typed candidates plus evidence; the common
  compiled plan performs identity and reduction.
- Add an architecture guard that prevents datasource providers importing the model's
  canonicalization engine or implementing their own key/reducer vocabulary.

A shared normalization abstraction is explicitly **not** part of this milestone. It is
introduced only when another provider presents a second concrete implementation whose
mechanics can be factored without source-specific knowledge leaking across the boundary.

### Milestone 6 — one class UI and explanatory preview

- Factor the Identity and Same-key sections into one editor used by every class construct.
- Offer only reducer choices valid for the field datatype/cardinality.
- Add a sampled before/after reduction view and actionable conflict report.
- Update effective-class explanations and the pipeline diagram to show production,
  grouping and reduction as separate stages.

### Milestone 7 — remove parallel paths and regenerate

- Delete `DuplicatePolicy`, `primaryListField` inference and construct-specific merge
  loops after all callers use the compiled plan.
- Verify Generate class, Generate domain, Remap, Enrich and Sample consume the same plan.
- Regenerate Nobel, Oscars and History; use their counts and collision reports as forcing
  acceptance tests rather than migrating old snapshots.

## Acceptance criteria

- An entity class and a statement class can use the same configured content key and
  reducers.
- The default entity configuration explicitly uses provider-qualified source identity.
- Nobel declares category/year/motivation agreement and laureate union without a custom
  statement merge path.
- A conflicting scalar is reported; encounter order never silently chooses it.
- Aggregate construction uses the same key/reducer vocabulary while remaining a separate
  class-producing operation.
- Adding a datasource requires candidate production and mapping, not a new identity or
  merge implementation.
- The configuration panel and execution log explain the same compiled plan.
