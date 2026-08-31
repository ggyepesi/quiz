# Models and domains

Status: model configuration and model use are implemented — the latter as adoption, see
step 2. Instance ownership is deliberately deferred.

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

## Step 2: model use (implemented as adoption)

A class is **adopted** into another project: it lands there as a real class that records
where it came from, in `GeneratedClassModel.originModel`. The stamp is applied at the one
point a class crosses projects and only when absent, so a class adopted from People and
relayed onward still names People — the project in the middle passed it along rather than
authoring it. `qualifiedClassName()` renders `People.Person` from that stamp; no qualified
name is stored, so none can drift from the class it names.

**The origin owns the field configuration.** `fieldsLocked()` is the one place that is
decided, and Add field, Remove and the field editor all ask it rather than testing for an
origin themselves. Removing the class is still allowed: dropping an adoption is the
adopting project's decision, not the origin's. How an adopting project may override fields
is deliberately open until there is experience with models — and nothing generated so far
is expensive enough to regenerate to force the decision now.

**Which models a project uses is derived, not declared.** A project uses a model exactly
when it holds a class adopted from it, which the stamps already record; `ModelUse` computes
it. A declared list beside them would be a second way to know one fact, free to disagree
with the classes actually present. The consequence is accepted: a use begins with the first
adopted class and ends with the last, and a model cannot be declared as used before
anything is adopted from it. If something later needs that, it is a new construct with a
reason.

**Who may copy from whom.** A domain adopts from models only — a domain-to-domain copy
would duplicate a configuration with no model between them to own it. A model may copy from
anything, since factoring a class out of a domain that already has it configured is how the
first model gets built. `DomainStorage.copySourcesFor` is where that rule lives.

Two bullets of the original plan dissolved rather than being built. **Circular use** cannot
arise: adoption is a copy, and a copy does not recurse. **Stored qualified references** are
unnecessary: a field targets a local class by its own name, and the qualified form is
display. Name collisions were already handled by the import plan's replace/reuse choice.

Still open: nothing yet reports that an origin has moved since a class was adopted from it.
The domain signature mechanism answers the equivalent question for instances, and the same
shape would answer this one, but no measurement forces it yet.

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
