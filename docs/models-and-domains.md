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

A model stays editable. Freezing one after its first use would make the first typo
permanent, and a schema that cannot be corrected gets copied instead — the divergence this
exists to prevent. The problem freezing would solve is already solved: a domain records the
signature of the configuration it generated against, so a model that moves afterwards makes
its domains report that their instances are stale, and regenerating settles it. That is the
same mechanism a domain's own edits already use, and it needs no new state, no used-yet flag
and no acceptance dialog.

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

**Import** uses a model's class where it stands. The class stays owned by the model named
in `GeneratedClassModel.importedFrom`, and is not edited in the importing project at all
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

**Which models a project uses is derived, not declared.** A project uses a model exactly
when it holds a class imported from it, which `importedFrom` already records; `ModelUse`
computes it and the Uses section reports it. Copies never appear there. A declared list
beside the imports would be a second way to know one fact, free to disagree with the
classes actually present. The accepted consequence: a use begins with the first imported
class and ends with the last, and a model cannot be declared as used before anything is
imported from it.

**Circular use** cannot arise — import materialises a copy of the configuration, and a
copy does not recurse. **Stored qualified references** are unnecessary: a field targets a
class by its own name, and `qualifiedClassName()` derives `People.Person` for display.
Name collisions are handled by the import plan's replace/reuse choice.

Still open: nothing reports that an owning model has moved since a class was imported
from it. The domain signature mechanism answers the equivalent question for instances and
the same shape would answer this one, but no measurement forces it yet.

Also deliberately open: whether an importing project may ever override an imported class,
and in what form. That decision wants experience with models, and nothing generated so far
is expensive enough to regenerate to force it now.

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
- automatic configuration changes of any kind.

Factoring classes from a domain into a model remains a desired explicit workflow, but it belongs
after model use is defined. It must preview its exact changes and run only after user approval.
