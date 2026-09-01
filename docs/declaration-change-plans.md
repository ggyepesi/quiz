# Declaration change plans

Status: design agreed; implementation deliberately follows after review.

## Purpose

Copy and Remove are changes to a connected model, not operations on one isolated class.
Before either action mutates anything, ModelBuilder must compute and show the complete
declaration impact: deep class dependencies, owned rules and selections, target conflicts,
and references that would otherwise be orphaned.

The UI does not derive these answers. One model-level construct computes them; the dialog
renders that result; Apply executes that exact result.

## The declaration graph

`ModelDeclarationGraph` is the single index of declarations and their relationships.
Its nodes are:

- classes;
- fields, including nested fields;
- selections;
- entity-kind rules.

It records two kinds of edge.

### Ownership

An owned declaration has no useful independent meaning when its owner is removed:

- class owns its fields recursively;
- class owns entity-kind rules that classify entities as that class;
- a role selection is owned by its declared class/field production site.

Owned declarations follow their owner in both Copy and Remove.

### Reference

A reference means the source declaration needs the target to remain valid:

- class extends base class;
- entity field targets class or selection;
- statement source names a source class or value selection;
- aggregate source names its source class;
- source bindings name their class, field or selection target;
- any other declaration reference already validated by `GeneratedProjectModelValidator`.

Reference edges have a path and a readable reason, for example:

```text
NobelPrize.laureates → Laureate
Person.structuredName → Name
Person classification rule → Person
```

The graph must reuse the model's existing reference-resolution rules. It must not introduce
a second name-only interpretation of class, field or selection ownership.

## One plan shape

`DeclarationChangePlan` is immutable and contains:

- operation: `COPY` or `REMOVE`;
- requested declaration;
- ordered effects;
- blocking problems;
- the exact source and target projects where applicable.

Each effect contains:

- declaration kind and visible name;
- action: `ADD`, `REPLACE`, `REMOVE`, `RETAIN`, or `BLOCK`;
- reason;
- the dependency path from the requested class.

`BLOCK` is an answer, not an exception discovered after confirmation. A plan with blocking
effects cannot be applied.

## Copy semantics

Copy follows the requested class forward through its complete required closure:

1. the requested class;
2. every required class dependency, recursively;
3. fields owned by those classes;
4. entity-kind rules owned by those classes;
5. required selections.

Incoming references do not follow the copy. A class that happens to refer to `Person` is not
part of copying `Person`; the dialog may state this boundary, but it is not an effect.

The target owns everything copied. There is no remaining relationship to the source.

For every same-name target declaration, the plan says `REPLACE`; otherwise it says `ADD`.
Replacement is never implicit in the dialog. The user either confirms the shown replacement
or cancels. A required dependency may be `RETAIN` only when the existing target declaration
is explicitly chosen and satisfies the same reference contract.

Example:

```text
Copy Person from Oscars to Person

Classes
  ADD      Person                 requested
  ADD      Name                   Person.structuredName → Name

Rules
  ADD      P31 = Q5 → Person      owned by Person

Selections
  (none)
```

## Remove semantics

Remove begins with the selected class and its owned declarations. It then walks references
in the reverse direction.

Owned consequences are safe members of the same removal:

- all fields of the removed class;
- its entity-kind rules;
- role selections owned exclusively by its fields.

Incoming references are not silently deleted. They are reported as orphaning impacts. The
first implementation should block confirmation until the user has either:

- removed or redirected the referencing declaration beforehand; or
- explicitly selected a cascade, causing its owning class to join the removal plan.

Cascade is recursive and uses the same graph. If removing `Name` requires removing `Person`,
and removing `Person` would orphan another class, all of those effects appear before Apply.
A root class is a blocking boundary and can never silently enter a cascade.

Shared declarations are not removed merely because they become unused. An unreferenced
vocabulary can be cleaned up later by an explicit Remove action; unused is not owned.

Example:

```text
Remove Person

Classes
  REMOVE   Person                 requested

Rules
  REMOVE   P31 = Q5 → Person      owned by Person

References that would be orphaned
  BLOCK    Award.recipient        field targets Person
```

With an explicitly approved cascade:

```text
Classes
  REMOVE   Person                 requested
  REMOVE   Award                  Award.recipient → Person

Rules
  REMOVE   P31 = Q5 → Person      owned by Person
```

## Dialogs

Copy and Remove use the same plan renderer with operation-specific wording. Sections list
actual declarations, never only counts:

- requested class;
- dependent or cascading classes;
- rules;
- selections;
- replacements;
- orphaned references or other blockers.

The confirmation button names the action (`Copy 2 classes`, `Remove Person and 1 rule`) and
is disabled when the plan contains `BLOCK` effects. Nothing mutates while the dialog is open.

## Application

The plan applies to a deep copy of the target model, validates the complete result, and only
then replaces the live model. Preview and Apply must not recompute different closures. If the
model changed after the plan was built, application refuses and asks the user to review a new
plan rather than executing stale consequences.

`ClassImportPlan` must not remain as a second dependency derivation. Its closure logic is
factored into `ModelDeclarationGraph` and Copy delegates to `DeclarationChangePlan`. Import
uses the same forward closure but persists a live model reference instead of copied effects.

## Forcing tests

At minimum, tests establish that:

- a deep dependency chain is listed in order;
- copying `Person` includes `Name` and the `Q5` kind rule;
- incoming references are not copied;
- removing a class removes its owned rule;
- removing a referenced class reports every orphan path;
- cascade is recursive and refuses to absorb the root;
- a shared selection is retained;
- conflicts are visible as `REPLACE`, never discovered only during Apply;
- preview and Apply use the same effects;
- a changed model invalidates an already-built plan without partial mutation.

## Deliberately outside this design

- generated snapshots and their freshness;
- reverse tracking across saved domains;
- model versions or migration;
- automatic cleanup of merely unused declarations;
- inferred user choices during a cascade.

