# Models and domains

Status: model configuration and model use are implemented — the latter as copy and
import, see step 2. Instance ownership is deliberately deferred.

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

**Copy** eases configuring a class that is the same as, or similar to, one already
configured elsewhere. The result belongs to the project that copied it: freely editable,
renamable, no lasting relationship, no record of where it came from. A copy may start
from any saved project. Where it was copied from constrains nothing, so nothing needs to
be remembered about it.

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

**The import reference is the source of truth.** `ModelUse` derives the Uses section from
those references; the resolved classes are their current view, not a second declaration.
Copies never appear there. A use begins with the first selected imported class and ends
with the last.

Circular imports are refused when references are resolved. **Stored qualified class names**
are unnecessary: a field targets a
class by its own name, and `qualifiedClassName()` derives `People.Person` for display.
An import refuses a local name collision; Copy remains the explicit replacement operation.

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
class, and in what form. That wants experience with models first.

## Step 3: instance ownership and reuse

After model use works, decide separately:

- where instances conforming to model classes are stored;
- whether two domains retain separate projections of the same source entity;
- how shared source facts are reused;
- how model-derived instances enter a domain snapshot;
- how conflicts, provenance and domain membership are represented.

No storage or merge policy is implied by configuring or using a model.

## Explicitly deferred

- model versions;
- update pins and content digests;
- extension and field omission;
- shared instance repositories;
- automatic extraction of classes from a domain;
- automatic configuration changes of any kind;
- any record, in a model, of the projects that import it (see *What a model owes its
  importers*).

Factoring classes from a domain into a model remains a desired explicit workflow, but it belongs
after model use is defined. It must preview its exact changes and run only after user approval.
