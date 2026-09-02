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

## Persistence and regeneration

Contextual representations are authored model declarations and use stable class references.
Renames follow those references and validation rejects missing source/target classes or a
target without an admission rule. Existing snapshots are reproducible data: changing these
rules requires regeneration, not a compatibility migration.
