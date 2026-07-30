| Original Java world | Dynamic world | Status |
|---|---|---|
| `Viewable` instance | `WikidataDynamicObject` | Clean |
| `getIdentifier()` | `qid` plus `typeKey` | Clean |
| `getDisplayName()` | dynamic object `name` | Clean |
| `getReferenceLabel()` | persisted `referenceLabel` | Clean |
| Java class | logical `typeName/typeKey` | Clean |
| Declared Java field | dynamic map entry | Clean |
| Field annotations | `FieldRef` → `SnapshotFieldGraph` | Clean in TransformApp; incomplete in web |
| Null/empty declared field | schema entry without instance value | Clean |
| Object reference | typed snapshot `Ref` | Clean and cycle-safe |
| Collection | dynamic collection | Mostly clean |
| Map | list of map values | Lossy |
| `ViewableGroup` | dynamic object plus adapter | Semantically correct, unnecessarily indirect |
| `parent/children/members` | ordinary dynamic references | Clean |
| One explicit group root | inferred parentless group | Needs correction |
| `GroupView`/`GroupTreeBrowser` | same generic `ViewableGroup` renderer | Clean once root is supplied |
| `@Minor/@Inline/...` | schema flags | Correct model; not consumed everywhere |
| `ViewConfig` fields | schema/config rows | Still has backing-specific branches |


| Java world | Dynamic world |
|---|---|
| `Viewable` | dynamic `Viewable` with `FieldSet` |
| Java fields | dynamic field map |
| annotations | persisted `FieldSchema` |
| object references | typed snapshot references |
| collections | collections |
| maps | maps with preserved keys |
| `ViewableGroup` | shared dynamic `ViewableGroup` adapter |
| group root | explicit snapshot `groupRoots` reference |
| generic card renderer | same schema-backed `FieldSet` |
| generic group renderer | same `ViewableGroup` interface |

| Mapping | Status |
|---|---|
| Field annotations → persisted `FieldSchema` | Clean for v5; legacy structural metadata remains alongside it |
| `ViewableGroup` → dynamic group adapter | Clean v5 path; retains legacy `structuralObject`/`structuralPath` and reference-label fallback |
