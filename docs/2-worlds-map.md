# Two worlds: `Viewable`/`ViewableGroup` ⇄ dynamic snapshot

A living checklist. Each non-Clean status is a work item; flipping it to Clean is the
definition of done.

| Original Java world | Dynamic world | Status |
|---|---|---|
| `Viewable` instance | `WikidataDynamicObject` (`FieldSet`-backed) | Clean |
| `getIdentifier()` | `qid` plus `typeKey` | Clean |
| `getDisplayName()` | dynamic object `name` | Clean |
| `getReferenceLabel()` | persisted `referenceLabel` | Clean (legacy `structuralPath` fallback still present) |
| Java class | logical `typeName/typeKey` | Clean |
| Declared Java field | dynamic field-map entry | Clean |
| Field annotations | `FieldRef` → `SnapshotFieldGraph` / persisted `FieldSchema` | Clean in TransformApp; not consumed in web render |
| Null/empty declared field | schema entry without instance value | Clean |
| Object reference | typed snapshot `Ref` | Clean and cycle-safe |
| Collection | dynamic collection | Mostly clean |
| Map | dynamic map, keys preserved | Clean (non-`String` keys coerced via `String.valueOf`) |
| `ViewableGroup` | shared read-only dynamic adapter | Clean |
| `parent/children/members` | ordinary dynamic references | Clean |
| One explicit group root | persisted snapshot `groupRoots` | Clean |
| `GroupView`/`GroupTreeBrowser` | same generic `ViewableGroup` renderer | Clean |
| `@Minor/@Inline/...` | schema flags | `@Minor` round-trips (persisted role/keyRef); not consumed in web render |
| `ViewConfig` fields | schema/config rows via `FieldSet` | Mostly clean; no-instance reflection path + minor-bar dynamic guard remain |

## Follow-ups (remaining tails)

- **Legacy structural metadata.** `structuralObject`/`structuralPath` (fields, Entity JSON,
  save/load plumbing) and the `getReferenceLabel()` fallback are retained for
  legacy-snapshot reads only — fresh v5 data leaves them `false`/empty. Retire after old
  snapshots are regenerated.
- **`ViewConfig` backing branches.** Enumeration is unified through `FieldSet.of()`, but a
  class-only config table (no instance) still reflects the class directly, and the
  "All minor fields" bar still branches on `sample instanceof DynamicFields`.
- **Annotations in web render.** Schema flags (`@Minor`, …) are consumed by the
  TransformApp/fieldconfig but not yet by the web card renderer.
