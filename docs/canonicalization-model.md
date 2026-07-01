# Canonicalization model (agreed spec)

Every Quizable class is **canonicalized**: it declares how to derive a stable
**identity** and a human **displayName**. These are *rules on the class*, not
stored `name`/`qid` data fields — which is what removes the class of bug where a
loaded field named `name` competed with the identity and poisoned sort/search
(see the Oscars "alphabetical start, confused end" incident).

## 1. Quizable contract

- `getIdentifier()` — stable unique key, **never blank**; basis for dedup, map
  keys, equality.
- `getDisplayName()` — human label, **never blank** (falls back to identifier).
- `name` and `qid` are **reserved identity concepts, not data fields.**

## 2. Class kinds

Each class declares a kind:

- **WIKIDATA_ENTITY** — backed by a Wikidata item (has a `qid`).
- **DERIVED** — reified/composed (e.g. `Nomination`); no single `qid`.

## 3. Identity + displayName rules

| Kind | Identity | displayName |
|------|----------|-------------|
| WIKIDATA_ENTITY | `qid` | Wikidata `label` (+ language, default `en`) |
| DERIVED | **natural key** = ordered key fields (default: the reification **grain**); `getIdentifier()` = canonical join/hash of those values | **Field** (single-valued) or **Template** over fields |

- **Field-mode displayName and identity key fields require `SINGLE` cardinality.**
  A single reference resolves via the referent's displayName. Combining
  `COLLECTION` values into a label is **Template-only** (with an explicit join).

## 4. Reserved names

No data field may be named `name`/`qid`. On add/import the builder **auto-renames**
(e.g. a native-name property → `nativeName`) with a visible warning — not a hard
reject.

## 5. Model representation (engine)

- `CanonicalSpec` on `GeneratedClassModel`:
  `{ kind, keyFields[], displayNameMode = LABEL|FIELD|TEMPLATE,
     displayNameField, displayNameTemplate, labelLanguage }`.
- `getIdentifier()`/`getDisplayName()` on the materialized object are computed
  from `CanonicalSpec`.
- Remove `ensureNameField()` and the implicit `name` field; drop the
  `"name".equalsIgnoreCase` `isNameField()` heuristic (identity becomes explicit).
- **Back-compat:** a model with no `CanonicalSpec` (old file) **infers** one on
  load — reified class → DERIVED (grain key, primary field displayName); else
  WIKIDATA_ENTITY (qid + label). Nothing else in the file is disturbed.

## 6. Sort / search / config

`QuizableFieldPaths` surfaces the displayName **once** and identity **once**
(deduped). No model field can double or compete with them.

- **Surface title** stays `name` (back-compat with existing field paths like
  `["episodes","name"]`).

## 7. UI (kind-aware "Identity & label" section per class)

- WIKIDATA_ENTITY → `qid` + `label`(+language), auto.
- DERIVED → identity key fields (prefilled from grain, editable) · displayName
  `Field ▾ | Template …`; the `Field` dropdown offers only `SINGLE`-cardinality
  fields.
- A class cannot be saved without a resolvable, non-empty displayName.

## 8. Migration — assisted, in-place, preserving

All existing config (field mappings, subclasses, facets, qualifier PIDs,
transforms, `extends`) is carried over unchanged. Only the canonical
identity/displayName is added and the loaded `name` field converted, with a
**per-class diff** you approve (`+CanonicalSpec`, `−loaded name`,
`renamed X→Y`); a backup is kept. Then regenerate *data* against the migrated
models — no hand-repetition of config.

## Build order

1. `CanonicalSpec` on `GeneratedClassModel` + back-compat inference (+ tests). ← this step
2. Field-path dedup (identity surfaces once).
3. Codegen/mapper compute identity + displayName from `CanonicalSpec`; drop
   `ensureNameField`/string `isNameField`; reserve `name`/`qid`.
4. UI "Identity & label" section.
5. Reconfiguration assistant (load model → propose → confirm → diff → save).
