# The Subject of a Statement Is a Role

## Status

Implemented statement-model rule, written from one observation: History's Person read
*"Derived from Person.spouse (P26)"*. The code now requires subject handling to be
explicit and uses the compiled subject throughout validation, explanation and runtime.
History's authored configuration still requires the modeller to introduce the role class
described below; it is deliberately not changed in the background.

## Definition

A Statement class reifies one statement occurrence whose required core is:

```text
subject entity ── property ──▶ object/value
```

Qualifiers, references and rank describe that occurrence. Canonical keys decide when
several acquired occurrences represent the same modeled record; they do not provide a
second definition of a Statement class.

The property is declared by the Statement source and the object by its value field. The
subject must be handled explicitly in one of the forms the existing domains require:

- one direct `STATEMENT_SUBJECT` entity field;
- a scalar entity qualifier explicitly falling back to `STATEMENT_SUBJECT`;
- a `STATEMENT_PARTICIPANTS` entity list, which explicitly combines the subject with
  qualifier values.

The latter two are how a statement subject can feed a contextual modeled role without
inventing another statement grammar. An otherwise unmapped `AUTO` entity field is not
silently interpreted as the subject.

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
carrying the statement's own PID) but had no equivalent for the subject. History's saved
`source` field is `AUTO`, so it states no subject role.

So the subject was invisible to everything that asked. Moving the explanation onto
compiled roles was right in principle — it removed a second statement grammar from the
advisor — but the construct it delegated to could not answer, and `source` silently fell
through to *"said about it"* with nothing filling it.

**The suite stayed green because the fixture marks the subject explicitly while the saved
model does not.** This is the same trap the reify tests hit by hand-building roles the
real data never contains. The guard now uses the unmarked shape.

The subject is now resolved beside the value from its explicit role. Compilation carries
that answer to explanation and reification, including the actual configured field name;
runtime no longer writes every subject into a field literally named `source`.

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

### This ambiguity is not allowed

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

The enforced rule: **a statement's subject-fed field must not name a class whose
membership depends on evidence the statement does not carry.** Where the target class has
an admission rule (`Person: P31 contains Q5`), the subject must target a role class, and
a representation rule promotes it. Pointing the subject straight at the admitted class
asserts the evidence rather than testing it.

`GeneratedProjectModelValidator` now checks both halves: every Statement class must
explicitly handle its subject, and a subject-fed field cannot directly target an
evidence-admitted class. Its error names the role-class plus representation-rule fix.

Value fields deserve the same question, but they are not the same case: `position` is
bounded by a seeded `Position` population, not by evidence about what each value is.
The rule above is deliberately about the subject, where the gap was measured.

## What changed in code

| file | change |
|---|---|
| `StatementFieldSemantics` | one explicit vocabulary for direct, fallback and participant subject bindings |
| `CompiledStatementSource` | carries `subjectField`, as it already carried `valueField` |
| `ProjectModelCompiler` | resolves both roles at compile |
| `ModelStatementReifications` | writes the subject to the compiled field rather than a literal `source` field |
| `EffectiveClassExplanations` / `StatementAnatomyPanel` | ask the resolved role |
| `MembershipPattern` | `derivedFrom` ranks by role; self-reference last |
| `GeneratedProjectModelValidator` | requires explicit subject handling and refuses unproved specialization |

Guards cover rejection of an unmarked subject, the three explicit subject forms, runtime
use of a non-`source` field name, refusal of direct evidence-admitted specialization, and
role-based population ranking.

## Open

- Whether `PositionHolder` is adopted, and under what name — the modeller's call.
- `label (PID)` is now formatted in ten places. They agree today; it is the shape that
  drifts, and one `PropertyDisplay.of(label, pid)` would collapse them.
- Whether a class reachable *only* by self-reference should say "derived from" anything
  at all. Ranked last today, which is enough while every real class has a better answer.

## Design plan — make the statement triple first-class

### One definition

A Statement class has one foundational configuration:

```text
subject entity ── relation/property ──▶ object/value
```

This is the only way to say what the Statement class represents. Subject discovery from
a bounded set of object values and reading statements from an already configured subject
population are acquisition strategies for the same triple, not alternative meanings.

The configured triple owns:

- the subject entity role and the field or projections that receive it;
- the relation/property PID and label;
- the object field, value kind, and entity class or vocabulary where applicable.

Qualifiers, rank and references belong to the statement occurrence but do not redefine
the triple. Canonical keys, duplicate policy and display configuration operate on the
modeled record after acquisition.

### Subject and specialization are separate decisions

The statement identifies its subject but does not normally prove the subject's final
modeled class. The subject is first assigned to a role class; evidence can then represent
that role as a more specific class:

```text
OfficeHolding
  subject entity: PositionHolder
  relation:       position held (P39)
  object entity:  Position
  object field:   position

PositionHolder -> Person when P31 contains Q5
```

The same structure applies to Nobel (`Laureate -> Person`) and Oscars
(`Nominee -> Person`). A subject that fails the evidence remains in its role class; it is
not discarded or falsely stamped as the specialization.

The object is not universally a "role". It is a role entity for P39 (`Position`), an
award/category for P166, and a nomination category for P1411. The general name in the
configuration is therefore **Object entity/value**.

### Three independent axes, and three different owners

Configuring a statement triple is three separable decisions. They are **orthogonal** —
any combination is legal — and, crucially, they are not owned by the same party:

| axis | what it decides | owner | rule |
|---|---|---|---|
| **shape** | which field receives the subject and the object, and what placeholder class each is typed as | the model | the importer **fills** what the model left open; it never **changes** what the model settled |
| **bounds** | the QIDs: which entities may be subject or object at all | the domain | the model may carry a default; an importer overrides it freely |
| **representation** | `PositionHolder` represented as `Person` when P31 contains Q5 | the importing project | each importer chooses independently; already true today |

The middle row is not a preference, it is forced. **Instances stay per-domain** — that
was decided when models and domains were split. Bounds are what produce a population, so
if bounds were model-owned and locked, every importer would be obliged to generate the
same population, which contradicts that decision outright. Bounds must be domain-owned.

Representation already works this way: `ClassImportPlan` carries an imported class and its
admission rule (`EntityKindRule`) but not representations, which live on the importing
project. That is what lets Oscars say `Nominee -> Person` without Nobel or History being
opted in — see [[contextual-entity-representation.md]].

So the odd one out is *shape*, not bounds — and even there the rule is narrower than it
looks. "Owned there, edited there" is about **changing a decision the model made**. A leg
the model never configured was never authored there, so an importer configuring it
overrides nothing; it fills a hole. That distinction is what makes this implementable
without reopening "may an importer override an imported class" (#132):

```text
fill     the model left the leg open      the importer configures it     allowed
change   the model settled the leg        the importer overrides it      #132, still open
```

How an end is bounded is worked out in [[bounding-an-entity-end.md]] — one construct
used for both ends, with the alternatives mutually exclusive rather than silently ranked.

The QIDs are in four places, and "override the bounds" has to mean all four or it leaks:

```text
StatementClassSource.sourceClassName          the subject population
StatementClassSource.valueSelectionName/Id    allowed objects (a vocabulary)
GeneratedClassModel.seedQids                  a class's own seeds
FieldSourceMapping.allowedQids / sourceQid    per-field allowed values
```

**An override must be visible.** Once two domains bound the same imported class
differently, *"why does this Position have 28 members and that one 400?"* is only
answerable if the editor distinguishes the model's default from this domain's override.
Silent divergence looks exactly like a bug. And per directive 9, showing the difference
must not be what creates it.

### Neither leg has to be configured

A statement class may leave its subject, its object, or both **unconfigured**. A model
declares shape and does not generate, so requiring it to settle both ends would make a
model answer a question only a domain has.

This is the same sentence the codebase already says about bounding — *"bounding an
acquisition is not a model's problem"* (`ModelValidationScopeTest`) — applied to the other
leg. So the rule is the sibling of the bounding rule, not an exception to it:

```text
MODEL    subject and object may be unconfigured
DOMAIN   both must be configured before it generates
```

An unconfigured leg is still **shown**, in the editor and in the explanation, as an
explicit *unconfigured* state rather than as absence. An unconfigured placeholder class
yields a **reference**: instances carrying identity and label and nothing else — the
referenced-only class of [[modelbuilder-constructs.md]]. Specialization is what gives it
fields, and it is optional.

One consequence must be taken deliberately: if a domain could generate with the subject
unconfigured, the subject would still have to land somewhere, and today
`ModelStatementReifications` falls back to inventing a field named `source` — exactly the
invisible convention this work removes. Requiring the destination **at domain level** is
what allows that fallback to be deleted rather than leant on.

### Explicit subject projections

Every acquired statement has exactly one subject. **Where a statement class does say
where that entity goes** — always, before a domain generates it; optionally in a model —
these are the supported forms:

1. one direct subject field, such as `OfficeHolding.holder -> PositionHolder`;
2. an entity qualifier field whose explicit missing-value policy copies the statement
   subject;
3. a participants collection that explicitly combines the subject with entity qualifier
   values.

The latter two retain the Oscar and Nobel configurations. Oscars may acquire equivalent
statements from different endpoints; its explicit projections place the subject in
`nominee` or `forWork` according to which contextual qualifier is absent, after which
canonicalization unifies the copies. This is still one acquired subject and one triple,
not several kinds of Statement class.

An unmapped `AUTO` entity field is never interpreted as the subject. Existing models that
relied on that shape must be corrected through the UI and regenerated rather than silently
migrated.

### Regenerate; do not preserve the old snapshot shape

This change has no snapshot-compatibility layer. Generated snapshots are reproducible and
generation is cheap enough that preserving an obsolete implicit subject convention would
cost more in permanent complexity than regeneration costs in time.

After correcting a Statement declaration:

- do not translate old `source` fields;
- do not infer a subject to keep an old snapshot loadable;
- do not migrate old statement records in place;
- invalidate or replace the affected snapshot by generating it again.

Compatibility is required for authored configuration only when there is a concrete reason
that configuration cannot be corrected through the UI. It is not required for generated
instances.

### Configuration UI

The Statement-class panel should present the model in this order:

1. **Subject entity** — role class and direct destination field, or the explicitly
   configured projection fields;
2. **Relation** — property label and PID;
3. **Object entity/value** — destination field, value kind, class or vocabulary;
4. **Subject representations** — evidence-based specializations such as
   `PositionHolder -> Person when P31 contains Q5`;
5. **Qualifiers** — their properties, types, cardinalities and missing-value policies;
6. **Identity and presentation** — canonical key, merge policy and display name.

“Statement subject” should no longer be primarily discovered in an ordinary field's
advanced `Load as` list. Field configuration may show the role and navigate back to the
Statement-class declaration, but the Statement panel owns it. The explanation view should
render the same triple and projections rather than deriving another description.

### Validation rules

Before generation, validation must require:

- a valid relation/property;
- one resolvable object/value field;
- explicit subject handling through one of the supported forms, **in a domain**; a model
  may leave it open (see *Neither leg has to be configured*);
- every subject destination to be entity-valued;
- at most one direct subject field;
- no direct subject-fed target whose membership requires evidence the statement does not
  carry—use a role class plus representation rule instead;
- unambiguous, valid qualifier and canonical-key paths.

Errors should name the missing part of the triple and the concrete configuration action
that repairs it.

### Single execution path

Compilation is the sole interpretation boundary. It resolves the subject, relation,
object and projections once into the compiled Statement source. Generation, reification,
sampling, explanation and the configuration diagram consume that compiled result; none
may rescan fields or assume a literal field name such as `source`.

### Implementation sequence

1. Define the compiled first-class triple and explicit subject projections, reusing the
   existing statement source and field-role types rather than adding a parallel model.
2. Move subject ownership into the Statement-class editor and make the triple visible as
   one configuration block.
3. Compile the authored declaration once and remove field-shape subject inference.
4. Make reification, sampling, explanations and diagrams consume the compiled triple.
5. Add forcing tests for the direct-subject, Oscar fallback and Nobel participants forms,
   including a direct subject field whose name is not `source`.
6. Configure History through the UI with a `PositionHolder` role and
   `PositionHolder -> Person` evidence representation, then regenerate it.
7. Remove or demote the redundant field-level subject controls only after every existing
   configuration is expressible through the Statement-class panel.

The implementation is complete when a modeller can understand the whole statement from
the Statement-class panel, the generated record follows exactly that declaration, and no
consumer has an independent rule for discovering its subject or object.
