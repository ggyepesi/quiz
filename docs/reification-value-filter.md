# Statement reification: the value filter is the source membership

A general lesson from a long Oscars debug session (2026-07-13). It applies to any
statement-reification class, not just Nominations.

## The structural invariant

A statement-reification class has a fixed shape:

- **Source class `S`** = "entities that have property **`P`** to any of a curated
  target set **`T`**".
  (OscarNominations = entities with `P1411` to the 59 Oscar categories.)
- **Reified class `R`** = "the **`P`**-statements of `S`'s members".
  (Nomination = the `P1411` statements of OscarNominations members.)

From those two definitions one thing is **guaranteed**:

> **The reified statement's value is one of `T`.**

A Nomination's `category` is necessarily one of the 59, because being related by
`P1411` to one of the 59 is exactly what made its subject a member of
OscarNominations. So the correct value filter for the reify's qualifier-load is
**`T` itself** — the *same* set the root membership query already uses.

## The bug this prevents

The qualifier-load needs to restrict the statements it fetches to the ones whose
value is a real target (else a generic property like `P1411` — shared by the
Grammys, Emmys, Nobel, … — drags in everything). There are two ways to express
that restriction, and they must not be confused:

1. **An explicit value QID set** (`valueQids`) — the 59 categories. Tight,
   deterministic, correct.
2. **A value *type*** (`valueTypeQid`, e.g. `Q19020` "Academy Award category") —
   a guess that the targets are all instances of that type.

The bug: when the reified value field had **no explicit `allowedQids`**, the loader
fell back to the value *type* `Q19020`. But **the Oscar categories aren't all
`P31=Q19020`** — Best Picture (`Q102427`) and Best Director (`Q103360`) are typed
differently. So:

- The load took the heavy **value-anchored** path over `Q19020` categories only.
- Entities nominated *solely* for Best Picture/Director entered the pool (the root
  query uses the explicit 59) but their statements never matched the `Q19020`
  filter → **586 orphans**, ~1582 lost nominations, count stuck at 13610.
- It was invisible: the root count was *correct and complete*, so the gap looked
  like non-determinism until we found entities with a `target` (direct `wdt:P1411`
  claim) but no `__Nomination` (loaded statement).

The value *type* was a separately-configured annotation that had silently drifted
away from the actual membership targets. The membership set is *structurally*
correct; the type is a guess.

## The fix

`ModelStatementReifications.deriveOne` — when the value field has no explicit
`allowedQids` **and** the source class's membership uses the **same property** as
the reified statement, inherit the source's membership target QIDs as the value
filter:

```java
if (valueQids.isEmpty()
        && stmtProp.equals(clean(src.instanceMapping().propertyPid()))) {
    // add src.instanceMapping().sourceQid() + additionalTypeQids() to valueQids
}
```

Now the reify's value filter is *derived from the structure* (identical to the root
query) instead of a possibly-wrong type. Oscars runs **entity-anchored** over the
exact 59 categories, so Best Picture/Director statements load.

The gate `stmtProp == source membership property` is what makes this general and
safe — it fires only when the reified value genuinely *is* a membership target:

- Reify `P1411`, source membership by `P1411` to `{T}` → inherit `T`. ✓
- Reify `P1411`, source membership by `P31=Q5` (a *type*, different property) →
  skip; a `P1411` value isn't constrained by a `P31` membership. ✓

## The consistency check

Even with inheritance, an *explicit* `allowedQids` can be narrower than the
membership. `valueFilterGaps(reification, project)` returns the source-membership
targets an explicit value filter fails to cover, and `reify()` logs a warning at
generation time:

> `⚠ consistency: the value filter misses N of the source class's membership
> target(s) … — statements to those WON'T load.`

So the mismatch surfaces when you generate, instead of after a puzzling count
regression. (A value *type* filter can't be checked locally — that needs a network
query for the targets' `P31` — so the check only validates explicit QID sets.)

## The takeaway

**A value derived from the structure beats a separately-configured annotation that
can drift.** Whenever a class is "entities related via property `P` to a curated
set", and you reify that same `P`, the reify's value filter *is* that set — don't
re-specify it as a type. This is the same theme as the #92 reify-decomposition
work: make the derived thing authoritative, not the inferred one.

Domains this applies to: awards/nominations, "members of {orgs}", genealogy edges
to a curated set (Greek mythology), "works about {subjects}", etc.

## See also

- `ModelStatementReifications.deriveOne` / `valueFilterGaps`
- `QualifierLoader` (entity-anchored vs value-anchored load; the recovery pass)
- docs/canonicalization-model.md, docs/sparql-generation-rules.md
