# Contextual entity representation

## Problem

An entity-kind rule currently does two jobs. It describes evidence for membership in a
class (`Person` when P31 contains Q5), and it globally changes every eligible carrier to
that class. Importing a model can therefore change another domain's representation without
that domain explicitly choosing it. It also makes several classes with the same evidence
compete to become one implicit "actual type".

## Model

Admission and representation are separate declarations.

- A class admission rule belongs to the class it describes. `Person: P31 contains Q5`
  means that the evidence permits Person membership. Admissions are non-exclusive and may
  overlap.
- A contextual representation belongs to the consuming role class. `Laureate -> Person`
  means that a Laureate satisfying Person's admission is represented as Person. The role
  class remains the fallback when no configured alternative matches.

The base does not have to produce a population of its own. A known extension may have its
own bounded producer; ordinary inheritance already makes every such instance a base
instance. Astronomy uses this form: `CelestialBody` supplies shared schema while `Star`,
and the Solar System model's `Sun`, `Planet` and `Moon`, produce their own bounded
populations. When unspecialized candidates do enter through a referring field or a normal
source membership such as `item P31 type`, the same ordered representation rules refine
them. These are alternative population entry points, not different specialization
mechanisms.

For Nobel this reads:

```text
Person model
  Person admits P31 = Q5

Nobel domain
  LaureatesWithMotivation.laureates -> Laureate
  Laureate represents matching entities as:
    Person, when Person admission matches
  otherwise Laureate
```

The same pattern covers a derived back-reference on an owned class:

```text
Discovery.owner -> Owner
Owner represents matching entities as:
  Constellation, when Constellation admission matches
  CelestialBody, when CelestialBody admission matches
otherwise Owner
```

The reusable Discovery model can declare `Owner` before it knows which consuming classes
will produce discoveries. A consuming model adds its explicit representation alternatives.
`Viewable` is only the renderer's Java interface and is never a role class or a model-visible
substitute for this declaration. `Owner` itself is authored vocabulary: synchronization may
derive that `Discovery.owner` needs it, but never creates or removes the class. Saving is
blocked with a direct validation error until a required role has been declared explicitly.

Oscars makes its own independent choice: `Nominee -> Person`. Importing Person supplies the
available class and its admission; it never silently opts a role into using it.

## Semantics

Representation alternatives are ordered. Every matching alternative contributes semantic
membership; the first matching alternative is the carrier used for presentation and field
loading. This makes precedence visible rather than deriving it from class names. The fallback
role is removed only after an explicitly configured alternative matches.

The configuration is shown on the role class and as a dashed edge in the model graph. The
project-wide Class admissions view remains an index of admission evidence, not a representation
editor.

In the class editor, each alternative is displayed together with the admission it uses, for
example `Person — P31 = Q5`. Up and Down make carrier precedence explicit; merely importing,
selecting, or inspecting an admission never creates this rule.

A local representation class may extend an imported one. `HistoricalPerson extends Person`
inherits Person's admission as well as its fields, so a History role may explicitly choose
`HistoricalPerson` without copying the `P31 = Q5` declaration. The admitted entity is stamped
as HistoricalPerson and receives the subclass's effective inherited-plus-local schema.

## Persistence and regeneration

Contextual representations are authored model declarations and use stable class references.
Renames follow those references and validation rejects missing source/target classes or a
target without an admission rule. Existing snapshots are reproducible data: changing these
rules requires regeneration, not a compatibility migration.
