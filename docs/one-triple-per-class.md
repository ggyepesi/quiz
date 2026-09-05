# One Triple Per Class

## Status

Implementation in progress. Written after a session of configuration-UI defects whose
common cause was one construct spelled three ways in three editors. It proposes a single
triple component and says what each class kind fixes, authors and only reads.

It does not propose new model constructs. Everything below already exists; what changes
is that it stops being said three times.

## The observation

Source, Statement and Owned classes all describe a **triple**. Only which of its tags are
authored differs. `RuleDirection` makes this literal — it has a method called
`triplePattern`, and it emits exactly the two arrangements:

```java
ROOT_TO_ITEM ->  root wdt:P  item .     // the class's members are the SUBJECT
ITEM_TO_ROOT ->  item wdt:P  root .     // the class's members are the OBJECT
```

So a Source class's membership is not "a type filter"; it is a triple with a direction
saying which end its members occupy.

| kind | the triple | fixed | authored | read-only |
|---|---|---|---|---|
| **Source** | `Constellation`: ⟨members, P31, Q8928⟩ | its members are one end | which end (direction), the property, the object bound | — |
| **Statement** | ⟨subject, P166, category⟩ | — | all three | — |
| **Owned** | ⟨members, production site, owner⟩ | its members are one end | — | the property and the object: both are settled by which field, on which class, declares the ownership |
| **Aggregate** | none | | | grouping by a key is not a statement |

Owned has a triple like the others; what differs is that two of its three tags are
authored elsewhere. Ownership is a statement about **production** — how these instances
are drawn from the datasource, on the owner's QID — and about nothing else. A part
inherits nothing from its owner: it is an instance of its class like any other, told apart
by that class exactly as any other instance is.

Its triple is stored on the owning field (`productionKind = OWNED_COMPONENT`,
`entityClassName`), so the property (the production site) and the object (the owner) are
both implicit, settled by which field on which class declares the ownership. A class may
be produced at several sites, and none of those triples is authored on the class being
edited. The component shows them and points at where each is authored — it must not offer
to edit them here, or the model would gain a second place to say one thing.

## What is spelled three times today

The same three tags, in three vocabularies:

```text
Source class     instanceMapping.propertyPid + sourceQid + direction
                 + additionalTypeQids + excludedTypeQids + discriminatorPid/Qid
                 labelled "Subtype", "Relation property", "Also include types", "Exclude types"

Statement class  statementSource.propertyPid + subjectBound + objectBound
                 labelled "Property", "Subject", "Object"

Owned class      the owner field's productionKind + entityClassName
                 labelled by its absence — the owned panel shows sites, not a triple
```

`docs/modelbuilder-constructs.md` already rules that the same concept gets the same words.
Three spellings of subject·property·object is that rule's largest outstanding violation.

## What the component holds

```text
TripleEditor
  members-end      which end this class's members occupy   (Source; fixed for Statement)
  property         the PID, with its label
  other-end        an EntityBound: Anything / These QIDs / Property + QIDs / A vocabulary
  destination      which field receives each end, and by which route (read-only here)
```

Per kind it is configured, not subclassed:

- **Statement** — both ends authored, no members-end. **Shipped.**
- **Source** — members-end authored (the direction), the other end an `EntityBound`.
  **Not shipped: the model change landed, the editor did not.** A source class's
  membership is one `EntityBound` now, but `ClassSourcePanel` still shows it as
  "Relation property" plus two QID boxes ("Wikidata type" and "Also include types") —
  one ordered list read as a leading target and the rest. Nothing is lost through them,
  and they are still a second spelling of the triple: the two boxes are the object row,
  the relation property is the property row, and the members are the subject.
  Everything hanging off those boxes moves with them — the type search, "Discover
  subtypes", "From parts", the QID links, and `useSourceQid` from the WikiProject and
  Explore panels, which is why this is a step of its own and not a rename.
- **Owned** — every row read-only, each pointing at the field that authors it.
  **Shipped.**
- **Aggregate** — the component is absent; there is no triple.

## Five things this resolves

### 1. `EntityBound` reaches Source membership

A Source class can say `sourceQid` plus `additionalTypeQids` — a positive set of objects
on one property. It cannot say "P279 subclasses of these, with closure", which
`EntityBound` expresses and the statement ends already use. Sharing the component gives
that positive population membership the bound the model already has.

`excludedTypeQids` is not another spelling of that bound: it is a negative filter applied
after the positive population is described. The subclass discriminator is different
again: it is a conjunctive restriction on inherited membership. Neither may be folded
into the one `EntityBound`, because doing so would silently broaden or otherwise change
the population. They remain separately named constraints until a composition construct
is forced; this refactor does not invent one.

### 2. Direction gets one name

`RuleDirection` has three vocabularies over it today: the enum's `ROOT_TO_ITEM` /
`ITEM_TO_ROOT`, its own "root → item" preview text, and "incoming"/"outgoing" in the
advisor. The memory rule already says direction is not a position — subject and object are
absolute places in the triple, incoming and outgoing are relative to the class you stand
on. In the component the question is asked once, as **which end the members occupy**.

### 3. The P31 literal, and the questions asked of it

Forty `"P31"` literals in `app/src/main`, one named constant (`MembershipFields.P31`,
private). Ten are in the workbench, doing four different jobs:

```java
new JTextField("P31", 5)                              // the default a reader sees
m.propertyPid().isBlank() ? "P31" : m.propertyPid()   // the default when nothing is stored
relPid.equals("P31") ? "instance of" : …              // its label
if (pid.isBlank() || pid.equals("P31")) …             // "is this the ordinary case?"
```

The component concentrates the first three. **The fourth is not a literal problem** and
must not be treated as one: `equals("P31")` standing for *"is this the plain membership
case"* is directive 12 — a decision read off a value that merely correlates with it. That
question gets a name of its own, or one constant will be compared in eight places to mean
eight things.

### 4. Graph expansion moves to the edge

`GraphExpansionPolicy` sits on BOTH `StatementClassSource` and `GeneratedFieldModel`, and
one plan reads both:

```java
for (GeneratedClassModel clazz : model.classes()) {
    if (clazz.statementSource().graphExpansionPolicy() != CURATED) continue;
    patterns.add(structuralPattern(model, clazz.className()));
}
return new GraphExpansionPlan(patterns, WikidataFieldGraphTraversal.derive(model));
```

An edge is what expands. The field's copy is on the right construct; the class-level one
is on the nearest thing that had a panel — and the panel admits it, showing
"⚠ Unavailable — requires direct subject discovery" on classes where the control cannot
apply. The class-level opt-in becomes what it is: this triple being followable.

Whether a whole-model "which edges may discovery follow" list reads better than a flag per
edge is a separate question, decided on its own rather than inherited from the current
shape.

### 5. Four editors stop disagreeing about class-level facts

Not part of the triple, but found by the same survey and fixed by the same decomposition:

| | className | alias | extends |
|---|---|---|---|
| Source | ✅ | ✅ | ✅ |
| Statement | ✅ | ❌ | ❌ |
| Owned | ✅ | ✅ | ✅ |
| Aggregate | ❌ | ❌ | ❌ |

Nothing in the model or the validator restricts alias or a base class by kind — the one
kind-specific rule is that an Owned class may extend only another Owned class, which
constrains what a base may BE. An aggregate class cannot be renamed from its editor at
all. These are class facts, not kind facts: `ClassHeaderEditor`.

## The rest of the decomposition

The triple is one of four shared components. The others, from the same survey:

- **`ClassHeaderEditor`** — name, alias and extends.

  **Alias** is a display alias authored with the class: what the UI shows instead of its
  name, pure presentation, with the class name staying the identity everything references.
  An imported class is a live reference, so its alias is read-only here just like its name
  and fields; a domain-local alias would be a separate presentation-override construct and
  is not introduced without a forcing use case.

  Import ownership belongs to `ModelSourceWorkbenchPanel`, which already locks the whole
  selected declaration and shows the single explanation that it is edited in its owning
  model. The header must not repeat that state or notice.
- **`ClassIdentityEditor`** — already shared by Source, Statement and Aggregate. **Done.**
  It now asks the missing-key question beside the key it is about, for every kind.

  The UI was not the last place holding both. `AggregateClassSource` still carried its own
  `MissingKeyPolicy` (EXCLUDE / GROUP, defaulting to EXCLUDE), `ModelAggregates`
  translated it into `canonical.MissingKeyPolicy` on the way to the one engine that acts
  on it, and `CompiledCanonical` did not carry the canonical one at all — so `toSpec()`
  gave back a spec that had forgotten it. Nobel's NobelPrize held both and answered them
  differently: EXCLUDE on its aggregate recipe, INCOMPLETE_GROUP on its canonical spec.

  The surviving enum's default is `INCOMPLETE_GROUP`, and the argument for it is measured:
  rejecting, run over the shipped snapshots, would have discarded 99 real records, and a
  default may only be non-destructive. That is the opposite of the aggregate default, so
  collapsing them changes what a new aggregate class does. Nobel keeps its behaviour
  because its authored answer was written onto the surviving field — 634 prizes, not the
  636 the other default produces. Changing that is a decision for the UI, which is now
  the only place it can be made.
- **`DisplayNameEditor`** — mode, field, template. Whole in `ClassSourcePanel`, a partial
  fourth copy in `StatementSourcePanel` (field only, silently preserving templates it
  cannot show), absent in Owned and Aggregate.

  **All four kinds get it, Owned included.** A part's instances belong to a class and are
  told apart by it like any other instances; ownership governs production, not naming.
  Owner-and-site is the DEFAULT — a part is produced on the owner's QID and so has no
  label of its own to take, and the default is what keeps it from reading as its owner.

  This was already broken rather than merely absent. `DomainFinalization` runs
  `Canonicalization.apply` and then `OwnedComponents.recomposeNames`; canonicalization
  skips only LABEL mode, not owned classes, so a FIELD or TEMPLATE name on an owned class
  was applied and then unconditionally overwritten two lines later. Two rules for one
  fact, agreeing only because the UI offered no way to configure one. `recomposeNames`
  now leaves a class that names itself alone.

### The statement panel's explanation goes with it

`StatementSourcePanel` carries a standing caption:

> Each instance is one statement — a **subject**, a **property**, and an **object** — with
> its qualifiers said about that statement. Subject and object are named by a class, which
> is a placeholder: unnamed it is served as a reference (identity and label), and it is
> specialized by evidence rather than asserted here.

It says four things, and after the unification the UI shows three of them: the triple IS
the component's shape; the qualifier fields carry their PIDs; and `Modelled as: no class
named — served as a bare reference` is the third. Prose restating what the controls show
is the rephrasing this session's rules already forbid.

The fourth clause — specialized by evidence rather than asserted here — is not about the
triple. It is the admission-versus-representation rule, and the validator already refuses
the mistake with the reader's own class names in it: *"A statement subject cannot directly
assert the evidence-admitted class 'X'. Target a role class and represent it as…"*. So the
caption pre-explains a refusal most models never trigger. Delete it, and if that clause
needs saying in the UI, say it where the choice is made — on the subject end, when the
target class IS evidence-admitted — not as a caption on a panel usually describing a model
already doing it correctly.

And one deletion: **no per-kind Apply buttons.** Three panels have one, with three
different names, one of them mid-panel above rows it appears not to cover; the statement
panel has none and works, because `ModelSourceWorkbenchPanel.applyEdits()` already flushes
the right editor before every save, generation, preview and kind switch. The buttons do
not make edits take effect; they make it look as though edits would not take effect
without them.

## Order

1. `ClassHeaderEditor` — the largest gap, no open questions.
2. ~~`ClassIdentityEditor` gains missing-key; the aggregate's private enum collapses.~~
   **Done.**
3. `TripleEditor` — **Statement done, Owned done, Source still open (the UI, no
   longer the model).**

   Statement authors all three tags in one box, and the subject's population moved
   inside it: naming the class whose members are the subjects is a way of bounding the
   subject end, and it was the one leg with a control outside the box named after the
   triple. Owned READS its triples in the same three words, one per production site,
   each saying where it is authored.

   **The Source model change is done** — `GeneratedClassModel.membership`, one
   `EntityBound`, replacing `instanceMapping`'s `propertyPid` + `sourceQid` +
   `additionalTypeQids`. Both rule-compiler paths, the datasource crossing, the
   membership pattern, the advisor, the validator and the shipped models read it; the
   six classes that carry a real membership were migrated, and the six that carried a
   bare default `P31` with no target became unbounded, which is what they meant.

   What it removed: the split between "the type" and "the extras" carried no meaning —
   every consumer re-joined them — and `PopulationSourceBindings.assign` tore a list back
   apart as first-and-rest. `effectiveInstanceMapping` decided a class had its own
   membership by asking whether `sourceQid` was blank; it asks the bound now.
   `CompiledCanonical` and `CompiledClass.membership()` both gained the fact rather than
   re-deriving it, which is what makes the two compiler paths agree by construction —
   the parity test used to pass because both made the same split.

   Two things it did NOT do, and step 3 is therefore NOT finished for Source.
   `seedQids` is the same construct in `EXPLICIT` shape and is still a second authored
   place, with silent precedence between them in `PopulationSourceBindings` (a relation
   wins, a seed list is only consulted when there is none) — 37 sites, its own step.

   And the source editor still shows the one bound as three controls: a relation
   property and two QID boxes, the second reading as "extra" types when the model no
   longer has a leading one. Folding them into the triple's object row is what brings
   Source into `TripleEditor`, and it carries the type search, "Discover subtypes",
   "From parts", the QID links and the `useSourceQid` action with it.

   Subclass closure is now persistable — the thing the adapter used to refuse — and no
   run performs it: `RuleNodeQueryBuilder.subclassMembershipBackboneQuery` carries none
   of the class's other membership filters, so swapping it in would drop them silently.
   The validator refuses a model that asks for one until that query is wired.
4. `DisplayNameEditor`, in all four panels; owner-and-site becomes the owned default
   rather than an unconditional rule.
5. Apply buttons removed.

Each step keeps the suite green and changes no saved data. Step 3 changes what a Source
class can express — `EntityBound` replaces its positive target fields while exclusions
and inherited discriminators retain their distinct semantics. This is the one place a
saved model would gain a shape it did not have, and so the one place to decide
regenerate-versus-migrate explicitly.

## Non-goals

- A generic "edit any triple" screen. The kinds differ in what they fix, and the component
  is configured per kind rather than pretending they are the same.
- Moving where anything is authored. Owned triples stay on the owning field; the component
  reads them.
- Changing `RuleDirection`'s values. Only what the reader is asked.
