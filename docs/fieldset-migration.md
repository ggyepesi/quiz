# FieldSet migration — one field construct for quiz.ui (#87)

## Problem

A domain object is fields either way — but the machinery reads them **two ways**:

- **Declared Java fields** via reflection (`QuizableAdapter.getField` / `getAllFields`),
- **A dynamic property map** on a `WikidataDynamicObject` / `DynamicQuizable`
  (`DynamicFields.dynamicFieldValues()`).

So ~15 sites across `quiz.ui` (and web/facet/transform) each re-`instanceof
DynamicFields` and fork the logic. The forks drift: e.g. field **enumeration** has a
reflection-only `QuizableFieldPaths.collect(config)` (misses a WDO's map-held fields,
so `forWork`/`category` were unsearchable) alongside the dynamic-aware
`collectFromSample`. Every new consumer must remember to handle both backings, and
the reflection branch silently under-serves dynamic domains.

## The intended fix already exists but is unadopted

`quiz.fields.FieldSet` is the seam: `FieldSet.of(obj)` returns a `DynamicFieldSet`
(map) or `ReflectionFieldSet` (declared) behind ONE interface — `fields()` + `read(name)`
— so a consumer never branches. Its own javadoc: *"This is the ONE interface the
machinery reads … so it never branches on `instanceof DynamicFields`. Nothing is
migrated onto it yet; this is the seam."*

## Goal

Every `quiz.ui` field operation — enumerate, read, render, search, sort, config,
serialize, facet — goes through `FieldSet` (and `FieldAccess`, which composes dotted
paths over it). Delete the `instanceof DynamicFields` branches and the reflection-only
enumeration. Behavior-preserving: same fields, same values, same paths.

## Sites (from the survey)

| area | file(s) | current fork |
|---|---|---|
| enumerate | `QuizableFieldPaths.collect(config)` (reflection) vs `collectFromSample` (FieldSet) | 5 vs 2 callers |
| read/write | `FieldAccess.readField` / `writeField` | `instanceof DynamicFields` |
| render | `QuizablePanel` (615, 740) | `instanceof DynamicFields` |
| serialize | `QuizableJson` (312, 337, 381, 441) | `instanceof DynamicFields` |
| facet | `FacetKeys` (55) | `instanceof DynamicFields` |
| web serve | `GeneratedSource` (163), `QueryObjectResultPanel` (156) | `instanceof DynamicFields` |
| transform domains | `SnapshotDomain`, `ReflectionDomain` | mixed |
| config editor | `QuizablePanelConfigEditor` (111/153/175/217) | dynamic vs reflection rows |
| search value | `QuizablePanelSearchAndSort.extractValue` (289) | `instanceof DynamicFields` |

## Migration order (each slice: replace the fork, run tests, no behavior change)

1. **Enumeration** — make `FieldSet`-based enumeration the single path. `collectFromSample`
   already goes through `FieldSet`; route `collect(config)`'s callers to it (with a
   sample) and retire the reflection-only enumerator, OR give `collect` a `FieldSet`
   backing. *(searchQuizables already routed — the first step.)*
2. **Read** — `FieldAccess.readField`/`writeField` → `FieldSet.of(obj).read(name)` /
   a `write(name,value)` added to the interface. This is the highest-leverage: render,
   search value-extraction, facet, and JSON all bottom out in field reads.
   *(DONE — 91b45b9. `FieldSet` gained `has(name)` to keep the layered map→reflection→identity
   fallback exact; `FieldAccess` has no `instanceof DynamicFields` left.)*
3. **Render / serialize / facet / web** — replace each `instanceof DynamicFields` with
   `FieldSet.of(obj)` / `FieldAccess`.
4. **Config editor** — its dynamic-vs-reflection row builders collapse into one
   `FieldSet`-driven builder (the `FieldTypeSource` stays for *modeling* type labels).

## Safety

Behavior-preserving refactor over count/render-critical code; per slice run the full
suite + spot-check a dynamic (Oscars snapshot) and a reflection (constellations/logs)
view. `FieldSet` must match today's per-site semantics exactly (identity `name`/`qid`
from the Quizable contract, `@NotQuizableField`/provenance skips, image-field filter).

## Out of scope

- `FieldTypeSource` / modeled type labels for references (the "ForWork/Quizable"
  display polish) — orthogonal; `FieldSet` unifies *access*, not *typing*.
