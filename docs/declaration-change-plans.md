# Declaration change plans

Status: reviewed against the code; corrected below. Implementation follows.

The first version is deliberately smaller than the design that was agreed. Four things
were cut because the code already answers them, answers them differently, or was never
asked: see *What review changed* at the end.

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
- action: `ADD`, `REPLACE`, `REMOVE`, `RETAIN`, `ORPHAN`, or `BLOCK`;
- reason;
- the dependency path from the requested class.

`RETAIN` is what a dependency the target already has is called when the user chooses not
to bring it — the existing declaration stays and the copy binds to it. That choice is the
dependency checkbox, which already exists and is tested. `RETAIN` is not a policy, not a
compatibility check between two declarations, and not a third answer to "this name is
already here": that question has two answers, replace or cancel.

`ORPHAN` reports a reference that will be left pointing at nothing. It does not stop the
operation — see *Orphaned references* below.

`BLOCK` is reserved for what genuinely cannot be done, and is an answer rather than an
exception discovered after confirmation. Today that is exactly two things: removing the
root class, and importing a class whose name a local declaration already holds. A plan
with blocking effects cannot be applied.

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
or cancels — the two answers an ordinary paste offers. A dependency the user deselects is
`RETAIN`: what is already there stays, and the copy binds to it.

A class the source only IMPORTS cannot be copied from it. The source does not own that
class, so the copy is taken from the model that does. This is already how the workbench
behaves and the plan states it rather than re-deriving it.

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

### Orphaned references

Incoming references are never silently deleted, and they do not block the removal either.
They are listed as `ORPHAN` effects, in full, before Apply.

Blocking them would take a stricter position than the model itself holds. An entity field
whose target class is gone is a WARNING and the project stays valid:

```text
AFTER removal, valid=true
WARNING: Award.recipient: Referenced entity class 'Person' is not modeled;
         the field renders as a string.
```

That is a recoverable state the validator already describes, with a stated consequence.
Refusing the removal would be a second, stricter reading of one fact — the thing this
design forbids itself two sections earlier. So Remove shows every orphan path and lets the
reader decide, and the warning that follows is the standing reminder.

If orphaning should instead be fatal, the fix is to make the validator say so; it is not to
disagree with it here.

### Cascade is not in the first version

Removing the referencing classes too, recursively, is deliberately left out. With orphans
reported rather than blocking, nothing forces it: the reader can remove what they meant to
remove and see exactly what it cost. Cascade is also the part most likely to take away
declarations the reader was not thinking about, which is a poor thing to build before
anyone has asked for it. A root class can never be removed at all.

Shared declarations are not removed merely because they become unused. An unreferenced
vocabulary can be cleaned up later by an explicit Remove action; unused is not owned.

### Removing an imported class

Removing a class this project IMPORTS is a different operation and the plan must say so. It
drops the import reference; it removes nothing from the model that owns the class, and it
has no owned consequences here, because the declarations belong to that model. Its effects
are the reference itself and any local `ORPHAN` left behind.

Example:

```text
Remove Person

Classes
  REMOVE   Person                 requested

Rules
  REMOVE   P31 = Q5 → Person      owned by Person

References left pointing at nothing
  ORPHAN   Award.recipient        field targets Person
                                  renders as a string until retargeted
```

The removal applies. `Award.recipient` keeps its name and becomes a string field, and the
project's own validation carries the reminder from then on.

Removing an imported class is the reference and nothing else:

```text
Remove Person   (imported from the People model)

Imports
  REMOVE   People.Person          this project stops using it

References left pointing at nothing
  ORPHAN   Award.recipient        field targets Person
```

Nothing is removed from People. Its Person, its fields and its rules are untouched, because
they were never this project's to remove.

## Dialogs

Copy and Remove use the same plan renderer with operation-specific wording. Sections list
actual declarations, never only counts:

- requested class;
- dependent classes;
- rules;
- selections;
- replacements;
- references left pointing at nothing;
- blockers, when there are any.

Replacements and orphans are stated where they will be read, not below a screenful of
description in a scrolling box. The line that says work will be lost is the one a reader
must not have to go looking for.

The confirmation button names the action (`Copy 2 classes`, `Remove Person and 1 rule`) and
is disabled only when the plan contains `BLOCK` effects — never for an orphan, which is a
consequence to be seen rather than a refusal. Nothing mutates while the dialog is open.

## Application

The plan applies to a deep copy of the target model, validates the complete result, and only
then replaces the live model. Preview and Apply must not recompute different closures.

There is no stale-plan guard. The dialogs are modal and nothing else writes to the model
while one is open, so the state it would detect cannot arise; detecting it would need a
model version that does not exist. If a background writer ever appears, this is where it
would be answered.

`ClassImportPlan` must not remain as a second dependency derivation. Its closure logic is
factored into `ModelDeclarationGraph` and Copy delegates to `DeclarationChangePlan`.

That closure has THREE callers, not two. Besides Copy and Remove, `ModelImportResolver`
asks it for `dependencyClassNames()` every time an importing project is loaded — so it is
on the path that opens Nobel and Oscars, and a change in what it returns changes what those
domains resolve. Import uses the same forward closure but persists a live model reference
instead of copied effects.

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
- an orphaned reference is reported and the removal still applies;
- removing an imported class drops the reference and touches the owning model not at all;
- copying is refused from a project that only imports the class;
- the root class cannot be removed;
- resolving an importing project yields the same classes as before the closure moved,
  since `ModelImportResolver` shares it — Nobel and Oscars are the cases that matter.

## What review changed

The design was written before the reference-import work landed and against assumptions the
code does not share. Four things were cut:

- **`RETAIN` as a policy.** It was `ConflictPolicy.REUSE_TARGET` under another name, and
  that was deleted deliberately: a name already here is replaced or the operation is
  cancelled. `RETAIN` survives only as the word for a deselected dependency.
- **Blocking on orphans.** The validator calls an orphaned reference a warning and keeps
  the model valid. Blocking would have been a second opinion about one fact.
- **Cascade.** Not forced once orphans are reported, and the most likely of these to remove
  something the reader did not intend.
- **The stale-plan guard.** Modal dialogs; the state cannot arise.

Two things were added: imported classes appear in both Copy and Remove, which the original
did not mention although the workbench already treats them differently; and the closure's
third caller is named, because it runs on every load of an importing project.

## Deliberately outside this design

- generated snapshots and their freshness;
- reverse tracking across saved domains;
- model versions or migration;
- automatic cleanup of merely unused declarations;
- inferred user choices during a cascade.

