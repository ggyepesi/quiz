# The Subject of a Statement Is a Role

## Status

Findings note, written from one observation: History's Person read *"Derived from
Person.spouse (P26)"*. Three separate things were wrong behind it — two are fixed in
code, the third is a **configuration** change only the modeller can make, and is
recommended, not applied.

## What was observed

The effective-class explanation for `Person` said:

```text
Instances : Derived from Person.spouse (P26); represented as Person when P31 = Q5
```

A self-reference cannot say where a class's instances come from: it presupposes the
population it claims to explain. The field that actually produces those people is
`OfficeHolding.source`, the subject of P39.

## Finding 1 — derivation was iteration order, not a role

`MembershipPattern.derivedFrom` returned the **first** entity field it happened to meet
that targeted the class. `Person` is the first class declared and `spouse` its first
entity field pointing back, so `Person.spouse` won. Nothing about the answer was
modelled; reordering the declarations would have changed it.

The roles on a reified statement *do* produce a population — the subject is the entity
the statement was found on, the value is what it pointed at. Derivation is now ranked by
role, with a self-reference last. `Person` reads `Derived from OfficeHolding.source
(P39)`; `Position` is unchanged at `OfficeHolding.position (P39)`.

## Finding 2 — the subject role was resolved nowhere

`StatementFieldSemantics` resolved the **value** field (the runtime non-qualifier field
carrying the statement's own PID) but had no equivalent for the subject.
`isStatementSubjectField` answered only when the modeller had explicitly set production
kind `STATEMENT_SUBJECT` — and models built through the UI leave it `AUTO`. History's
saved `source` field is `AUTO`.

So the subject was invisible to everything that asked. Moving the explanation onto
compiled roles was right in principle — it removed a second statement grammar from the
advisor — but the construct it delegated to could not answer, and `source` silently fell
through to *"said about it"* with nothing filling it.

**The suite stayed green because the fixture marks the subject explicitly while the saved
model does not.** This is the same trap the reify tests hit by hand-building roles the
real data never contains. The guard now uses the unmarked shape.

The subject is now resolved beside the value, by a rule rather than a guess: the runtime
entity field that is not a qualifier, is not the value field, and configures no property
of its own — because it is filled from the item the statement sits on. Two such fields
make it genuinely ambiguous, and that returns `""` for the modeller to resolve, exactly
as a missing value field does.

## Finding 3 — the subject is typed `Person` without evidence

This one is configuration, and it is the substantive one.

`OfficeHolding.source` declares `entityClassName = Person` directly. That asserts every
P39 subject **is** a human before any evidence is loaded. Nothing in a P39 statement says
so: the statement gives a subject QID and a position, and what that subject *is* only
becomes known when P31 is fetched for it.

The shipped snapshot shows the assumption failing:

```text
189 Person instances
188  P31 includes Q5
  1  P31 = Q1190554 only        Q66023226 "dethronement" — an occurrence
```

`dethronement` is stamped `Person` and served as one. It is not a person; it appeared as
a P39 subject, and the field's declared type made it one.

### The pattern that already handles this

Oscars and Nobel both solve it, and `docs/contextual-entity-representation.md` specifies
it. The role class carries whatever the statement produced; a separate representation
rule promotes it where the evidence matches, **and the role remains the fallback when it
does not**:

```text
Oscars    Nomination.nominee -> Nominee     Nominee -> Person
Nobel     …laureates         -> Laureate    Laureate -> Person
History   OfficeHolding.source -> Person    (no role class, no rule)
```

History has the admission half — an entity-kind rule `Person: P31 contains Q5` — but no
role class for the rule to promote *from*, so the promotion has nothing to refuse.

### Recommended configuration

```text
PositionHolder                          a role class, as Nominee is
OfficeHolding.source -> PositionHolder  the subject holds the role, not the conclusion
PositionHolder -> Person                represented as Person when P31 = Q5
```

Under this, `Q66023226` stays a `PositionHolder` — correct and visible — instead of
becoming a false `Person`, and the 188 genuine humans are represented as `Person`
exactly as now.

**Not applied.** This is user-authored model configuration, and the UI is its authority
(directive 10). It also changes what generation stamps, so it wants a regenerate rather
than a migration.

### This ambiguity should not be allowed at all

Recommending the pattern per domain is not enough — History reached a shipped snapshot
with a false `Person` in it, and nothing objected. Oscars already tells us the shape, and
Nobel is the same shape, so this is not a judgement call to be re-made per domain:

```text
Oscars    nominee -> Nominee    Nominee -> Person      role class + representation
Nobel     laureates -> Laureate Laureate -> Person     role class + representation
History   source -> Person      —                      conclusion asserted directly
```

Two of three domains already do it correctly. The odd one out is not a different
modelling opinion, it is a missing declaration — which is exactly the case directive 6
covers: encode the principle as a forcing test, so the next domain cannot repeat it.

The rule to enforce: **a statement's subject field must not name a class whose
membership depends on evidence the statement does not carry.** Where the target class has
an admission rule (`Person: P31 contains Q5`), the subject must target a role class, and
a representation rule promotes it. Pointing the subject straight at the admitted class
asserts the evidence rather than testing it.

That check has everything it needs already: `EntityKindRule` says which classes are
evidence-admitted, `EntityRepresentations` says which roles have a promotion, and the
subject role is now resolvable (Finding 2) — which is what previously made the rule
impossible to state. It belongs in `GeneratedProjectModelValidator`, beside the existing
representation-rule checks, and should name the fix rather than only refuse.

Value fields deserve the same question, but they are not the same case: `position` is
bounded by a seeded `Position` population, not by evidence about what each value is.
The rule above is deliberately about the subject, where the gap was measured.

## What changed in code

| file | change |
|---|---|
| `StatementFieldSemantics` | `statementSubjectFieldName` / `isStatementSubject` — the subject role, resolved once |
| `CompiledStatementSource` | carries `subjectField`, as it already carried `valueField` |
| `ProjectModelCompiler` | resolves both roles at compile |
| `EffectiveClassExplanations` | `partOf` asks the resolved role, not the stored production kind |
| `MembershipPattern` | `derivedFrom` ranks by role; self-reference last |

Guards: the unmarked-subject shape in `StatementPartsTest`, the resolution rules
(unmarked / explicit-wins / ambiguous-refused) in `StatementFieldSemanticsTest`, and the
ranking in `MembershipPatternTest`.

## Open

- Whether `PositionHolder` is adopted, and under what name — the modeller's call.
- `label (PID)` is now formatted in ten places. They agree today; it is the shape that
  drifts, and one `PropertyDisplay.of(label, pid)` would collapse them.
- Whether a class reachable *only* by self-reference should say "derived from" anything
  at all. Ranked last today, which is enough while every real class has a better answer.
