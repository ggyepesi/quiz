# Models and domains

Status: model configuration and model use are implemented — the latter as copy and
import, see step 2. Instance ownership is decided: instances stay per-domain, and nothing
is shared between domains.

## Rule number 1: keep it simple

A model is not a package, module, release or version. It has one user-given unique name.
There is no second machine-facing name, version coordinate, digest pin or hidden identity.
Those concepts may be introduced only if a concrete requirement later forces them.

## Model

A model is a reusable collection of class configurations. It uses the same configuration
language and editors as a domain:

- classes and fields;
- source mappings;
- vocabularies and populations;
- relationships and entity-kind rules;
- identity and presentation configuration.

A model may contain classes that are structurally valid but not independently generatable.
It has no generated instances and no snapshot. Consequently ModelBuilder does not show
Generate, Remap, Enrich or instance-result controls while a model is open. Explore, sampling,
validation and configuration remain available.

### What validation means for each

Validation asks two different questions, and only one of them is a model's to answer.

**Structural** — do the declarations make sense together? Names unique, references
resolving, no cycles through bases or owned components, field shapes consistent with what
they claim to be. A model whose classes do not hold together is wrong wherever it is used,
so these apply to both kinds.

**Runnable** — can this actually be acquired? Today that is one rule: a statement class
that discovers its subjects needs a bounded value domain, or its membership scan is
unbounded. That rule exists to bound an acquisition, and a model performs none, so it
applies to a domain only. The domain that gives the class a population is where the bound
has to exist.

The distinction is asked as `acquiresInstances()` rather than by comparing the kind,
so a skipped rule says why it was skipped.

The user supplies the model's unique name when creating it. The exact name is its identity.
Class names are unique inside their owning model. A later reference from another project will
use the visible qualified form `Model.Class`, for example `People.Person`.

A model stays editable. An importing project resolves its current configuration when it is
loaded, so a model correction becomes the configuration its importers use. This first version
does not inspect or rewrite their saved snapshots; how a changed model and an older snapshot
should be reconciled is deliberately left to experience with real imports.

## Domain

A domain applies configured classes to one or more populations and owns the result. It adds:

- generation entry points;
- Generate, Remap and Enrich operations;
- generated instances;
- a saved snapshot.

A domain is not restricted conceptually to one root class. The existing `rootClass` property is
a legacy generation/UI detail, not the definition of a domain.

## Current UI

**New…** asks for one name and whether the project is a Domain or Model. Both kinds use the
ordinary configuration tree and editors. Saving a model writes configuration only. Execution
and instance controls do not appear for a model.

The former Shared modules panel and shortcut are not part of this design. The committed
version/pin implementation is not exposed by the first-step UI and will be removed as model use
is implemented rather than carried forward as a compatibility contract.

## Step 2: model use — copy and import

These are two different acts sharing one mechanism, and keeping them apart is the whole
of this step.

**Copy and paste**, as anywhere else. Copy takes the class in front of the reader — the
source is where they are, so there is nothing to choose and nothing is asked. Paste puts
it into whichever domain or model they have opened since. The result belongs to the
project pasted into: freely editable, renamable, no lasting relationship, no record of
where it came from.

Pasting back into the project a class was copied from is refused. That is pasting a class
onto itself; wanting a second one is answered by renaming the first and pasting then, not
by the paste inventing a name. Where a name already exists in the target, the answer is
replace or cancel — the two an ordinary paste offers.

The clipboard holds a snapshot, not a live reference. Editing the source after copying
does not change what a pending paste produces; following later changes is what an import
does, and this is the other one.

**Import** uses a model's class where it stands. The importing project persists only the
model name and selected class names. On load those references resolve the model's current
class configuration and its required declarations. The resolved class is marked with its
owner for presentation and is not edited in the importing project at all
— not its fields, not its name, not its membership. It is edited in the model that owns
it. Only a model can be imported from.

`isImported()` is the single question every editor asks, so where a class may be changed
is decided once. The whole class editor is locked rather than each control, so a control
added to those editors later cannot quietly escape the rule. The class may still be
removed: dropping an import ends the use, which is the one thing about it the importing
project does decide. Ownership survives relaying — importing a class the source had
itself imported still names the model that owns it — and copying an imported class yields
an ordinary class, which is how a project takes a model's configuration and then diverges
from it.

The UI marks the difference: an imported class shows `imported from People` on its tree
row and a notice above its disabled editor naming the owning model, so a locked editor
reads as owned rather than broken. A copy carries no mark, because it claims nothing.

The lock applies to the declaration as a whole. In particular:

- the class name and alias cannot be changed in the importer;
- its membership/source configuration, including a Wikidata subtype/P31 declaration,
  cannot be changed in the importer;
- field names, types, cardinalities and source properties cannot be changed in the
  importer;
- the complete configuration is nevertheless shown, rather than being replaced by a
  read-only summary.

This is an ownership rule, not merely disabled Swing controls. The editor combines it
with the temporary global lock used during generation: returning the workbench to its
ordinary editable state must not reopen imported declarations. New controls inherit the
same rule through the enclosing editor instead of each remembering to implement it.

**Imported field names are deliberately stable.** Renaming `Person.familyName` in one
domain would create a local schema fork while still appearing to use the same model. It
would also make a future union of `Person` instances from several domains require a
per-domain field-name translation. The first version therefore forbids it. A project that
needs a different shape must copy the class and own the divergence explicitly.

**A subtype refinement may be useful later, but is not an ordinary edit.** An importing
domain may eventually need a population narrower than the model's declaration—for
example, a domain-specific restriction layered over an imported `Person`. If introduced,
this should be a separately named and visibly local refinement stored by the importer,
not an unlocked P31 box that appears to modify the imported class. It must only narrow
the imported population, leave the shared schema and field names unchanged, and make its
effect on generated domain instances explicit. Until that construct exists, subtype/P31
remains read-only with the rest of the class.

The importer also preserves presentation metadata exactly; it does not invent missing
labels. The first `Person` test exposed old fields whose PIDs were saved without property
labels (`P570`, `P734`, `P735`). That is corrected in the owning model, not patched in each
importer. The UI should show an absent label as absent rather than repeat the PID and
pretend it is a label.

**The import reference is the source of truth.** `ModelUse` derives the Uses section from
those references; the resolved classes are their current view, not a second declaration.
Copies never appear there. A use begins with the first selected imported class and ends
with the last.

Circular imports are refused when references are resolved. **Stored qualified class names**
are unnecessary: a field targets a
class by its own name, and `qualifiedClassName()` derives `People.Person` for display.
An import refuses a local name collision; Copy remains the explicit replacement operation.

### Example: a reusable statement class

An `OfficeHolding` class is a useful example because the statement structure is reusable
while the population to search is domain-specific.

The model owns the meaning and shape of one P39 statement:

```text
Model: Public office

OfficeHolding                         Statement class, reifies P39
  person       Person                statement subject
  position     Position              statement value
  startDate    Date                  qualifier P580
  endDate      Date                  qualifier P582
  replaces     Person                qualifier P155
  replacedBy   Person                qualifier P156

Identity: Wikidata statement identifier
Display:  {person} — {position}
```

`Person` and `Position` are class dependencies of `OfficeHolding`. Importing the statement
class therefore shows them in its deep dependency closure. The model may own them itself or
import them from other models; that does not change the statement shape.

What the reusable class deliberately does **not** say is “download every P39 statement from
Wikidata”. The importing domain supplies a bounded acquisition binding. History might say:

```text
History domain

Selection: RelevantPositions
  Apostolic King of Hungary
  Holy Roman Emperor
  ...

Binding for imported OfficeHolding
  statement values come from RelevantPositions
```

Execution then reads:

```text
RelevantPositions
        ↓ bounded values
P39 statement acquisition
        ↓
OfficeHolding instances shaped by the imported model
```

This binding is neither an entity-kind rule nor a field mapping:

- an entity-kind rule classifies an encountered entity, such as `P31 = Q5 → Person`;
- the statement binding decides which statements are acquired;
- a field consumes the resulting statement instances.

For example, History may configure:

```text
Person.offices = inverse of OfficeHolding.person
```

That inverse field presents the holdings belonging to a Person. It does not silently launch
P39 discovery; acquisition remains the explicit domain binding above.

The first model-import slice does not yet provide this domain-side binding editor for an
imported read-only statement class. Consequently, a statement class can currently be imported
only when its acquisition configuration is already complete and reusable. If its population
is domain-specific, it must currently be copied and configured locally. This example identifies
the missing construct without implementing an overlay or exception prematurely.

## Open: what a model owes its importers

Tracked as [#136](https://github.com/ggyepesi/quiz/issues/136).

Only the importer knows about an import. A model records nothing about who uses it, and
nothing asks it to. That is deliberate and it is the whole of the first version — it also
leaves one problem unsolved, named here so it is not mistaken for an oversight.

**The problem.** A model's classes can be changed, renamed or removed while other projects
import them. Nothing prevents it, nothing warns about it, and nothing records that it
happened. An importer finds out only when it next resolves:

- a class that **changed** resolves to its new shape, silently — which is the feature, and
  is also how a domain's generated instances can stop matching the configuration that
  produced them;
- a class that was **removed** fails with `Cannot import People.Person`;
- a class that was **renamed** is indistinguishable from one that was removed, because the
  import names it by name and nothing followed the rename.

The removal failure is legible on purpose. Legible is not the same as decided: it says what
went wrong, not what should have happened.

**What has to be answered.** Roughly in the order the questions bite:

- Does a model know which projects import it, or is that discovered by scanning them? A
  file that lists its own importers is book-keeping in two places that can disagree — the
  failure this design avoided for the use list.
- Is a rename followed, so importers retarget, or refused while the class is imported?
  Following it means writing to files the reader did not open.
- Is a removal refused while something imports the class, or allowed, leaving importers to
  fail? Refusing needs the answer to the first question.
- Is a change reported at all? Live resolution is the point, so most changes should pass
  through silently — but a change that invalidates generated instances is not the same
  kind of event as one that does not.
- Where does any of this get recorded, given a model is one file and its importers are
  others that may not be present?

**The constraint on any answer.** Every option that has a model track its importers adds
exactly the book-keeping this version does without. That needs a forcing reason, and
"a model ought to know" is not one. Until then a broken import is discovered at resolve
time, which is late but honest, and cheap to recover from: nothing here is expensive
enough to regenerate.

Separately open, and smaller: whether an importing project may ever override an imported
class, and in what form. The possible narrowing refinement described above is the first
concrete case; arbitrary field or identity overrides are explicitly not implied by it.

### Extending an imported class

A domain or model may author a local subclass whose `Extends` target is imported. For
example, History can declare `HistoricalPerson extends Person` and add `offices`, while the
imported `Person` remains live and read-only. The subclass inherits the base's fields and
its class-admission evidence, so it can be the explicit target of a contextual
representation without repeating `Person: P31 = Q5`.

Population is **not** inherited. Nothing walks the base for membership, seeds or an
instance mapping, so a subclass declaring no population of its own generates nothing —
it is a shape entities are represented as, not a class that acquires them. Admission
inherits as a fallback rather than an override: a subclass that declares its own rule
uses it, so the inherited one narrows where that is wanted.

Another model may import `HistoricalPerson`; resolving that import also resolves the model
that owns `Person`. Ownership remains exact at every level: `HistoricalPerson` belongs to
the intermediary model and `Person` to the original one. Domains and models use the same
mechanism. Field subtraction is deliberately not part of inheritance; a concrete need must
force an omission construct before one is added.

## Step 3: instance ownership — instances stay per-domain

**Decided: a domain owns its instances, and two domains that import the same class hold
their own.** If Nobel and Oscars both import `People.Person`, each generates its own Marie
Curie. Nothing is shared, and importing a class says nothing about where its instances
live — which is what kept step 2 small and is why this stayed a separate decision.

The reason is reproducibility. A domain's snapshot is currently derivable from that
domain's own model, and every part of the pipeline leans on it: regenerating is the answer
to almost every kind of drift, and it works because nothing outside the domain contributes
to its contents. Sharing instances would trade that away first and hardest, and a shared
store is recoverable later while reproducibility, once lost, is not.

The cost is accepted and worth stating plainly: identity work is repeated per domain, and
the same real entity drifts as each domain curates its own copy.

A model still generates nothing. It has no instances, no snapshot, and no store. "A
model's class" only ever means configuration; instances of it belong to the domain that
generated them.

### What sharing would have to answer first

Not built, and not to be started without a forcing reason — a measured cost of the
duplication above, not the observation that it exists:

- if two domains curate the same entity differently, which wins, and where is that
  recorded;
- what a snapshot means once part of its content came from outside the domain, and how it
  is then reproduced;
- how provenance survives a value produced in one domain and read in another;
- how domain membership is expressed for an instance no single domain owns.

Each of those is a mechanism this codebase does not have. Together they are the reason
this is a decision rather than a default.

## Explicitly deferred

- model versions;
- update pins and content digests;
- extension and field omission;
- shared instance repositories — decided against, not merely postponed (see step 3);
- automatic extraction of classes from a domain;
- automatic configuration changes of any kind;
- any record, in a model, of the projects that import it (see *What a model owes its
  importers*).

Factoring classes from a domain into a model remains a desired explicit workflow, but it belongs
after model use is defined. It must preview its exact changes and run only after user approval.
