# Models and domains

Status: the first step, model configuration, is implemented. Model use and instance
ownership are deliberately deferred.

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

## Step 2: model use (next)

Specify how a model is explicitly used by another model or a domain:

- present available models;
- add and remove a model explicitly;
- make its classes available in class selectors;
- store and display qualified references such as `People.Person`;
- distinguish an available class from one actually referenced by a field, rule or relationship;
- reject circular use and name collisions.

Importing a model shares configuration only. It must not decide where instances live.
Extending imported classes and omitting fields are later work.

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
