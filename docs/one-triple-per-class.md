# One Triple Per Class

## Status

Design proposal, no code. Written after a session of configuration-UI defects whose
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
| **Owned** | ⟨owner, production site, part⟩ | the part is one end; the site is a field | — | the whole triple: it is authored on the OWNER's field |
| **Aggregate** | none | | | grouping by a key is not a statement |

Owned is the case that must not be forced into the pattern. Its triple is stored on the
owning field (`productionKind = OWNED_COMPONENT`, `entityClassName`), a class may be
produced at several sites, and none of those triples belongs to the class being edited.
The component shows them and points at where each is authored — it must not offer to edit
them here, or the model would gain a second place to say one thing.

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

- **Statement** — both ends authored, no members-end.
- **Source** — members-end authored (the direction), the other end an `EntityBound`.
- **Owned** — every row read-only, each pointing at the field that authors it.
- **Aggregate** — the component is absent; there is no triple.

## Five things this resolves

### 1. `EntityBound` reaches Source membership

A Source class can say `additionalTypeQids` and `excludedTypeQids` — a list of objects on
one property. It cannot say "P279 subclasses of these, with closure", which `EntityBound`
expresses and the statement ends already use. Sharing the component gives membership the
bound the model already has, and retires two ad-hoc QID lists.

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

- **`ClassHeaderEditor`** — name, alias, extends, and the imported state.

  **Alias** is a display alias: what the UI shows for a class instead of its name, pure
  presentation, with the class name staying the identity everything references. Its point
  is an imported class — `Name` imported from the person model can read as "Structured
  name" locally without a rename that would break every reference to it.

  So the header carries the **imported** state, and must carry it rather than leaving each
  panel to decide again what imported looks like. An imported class's controls are all
  disabled, with a label above them saying the class belongs to another model — without
  which a disabled editor reads as merely broken. `ClassSourcePanel` and `OwnedClassPanel`
  each handle this today; `StatementSourcePanel` shows the class name without an alias, so
  it cannot show an alias it may not edit either.
- **`ClassIdentityEditor`** — already shared by Source, Statement and Aggregate. Gains the
  missing-key control, which exists on `CanonicalSpec` and **nothing edits**; the only
  missing-key UI is `AggregateClassPanel`, editing a *different* enum
  (`AggregateClassSource.MissingKeyPolicy`) for the same concept. The model already
  collapsed those two into `canonical.MissingKeyPolicy`; the UI is the last place holding
  both.
- **`DisplayNameEditor`** — mode, field, template. Whole in `ClassSourcePanel`, a partial
  fourth copy in `StatementSourcePanel` (field only, silently preserving templates it
  cannot show), absent in Owned and Aggregate. Open question: an owned part is named
  owner + site and must never take its owner's label, so this may be "not applicable"
  there rather than three modes.

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
2. `ClassIdentityEditor` gains missing-key; the aggregate's private enum collapses.
3. `TripleEditor`, Statement first (it authors everything), then Source, then Owned
   read-only.
4. `DisplayNameEditor`, once the Owned question is answered.
5. Apply buttons removed.

Each step keeps the suite green and changes no saved data. Step 3 changes what a Source
class can express — `EntityBound` where two QID lists were — which is the one place a
saved model would gain a shape it did not have, and so the one place to decide
regenerate-versus-migrate explicitly.

## Non-goals

- A generic "edit any triple" screen. The kinds differ in what they fix, and the component
  is configured per kind rather than pretending they are the same.
- Moving where anything is authored. Owned triples stay on the owning field; the component
  reads them.
- Changing `RuleDirection`'s values. Only what the reader is asked.
