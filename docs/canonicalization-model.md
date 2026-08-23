# Canonicalization model (agreed spec)

Every Viewable class is **canonicalized**: it declares how to derive a stable
**identity** and a human **displayName**. These are *rules on the class*, not
stored `name`/`qid` data fields — which is what removes the class of bug where a
loaded field named `name` competed with the identity and poisoned sort/search
(see the Oscars "alphabetical start, confused end" incident).

## 1. Viewable contract

- `getIdentifier()` — stable unique key, **never blank**; basis for dedup, map
  keys, equality.
- `getDisplayName()` — human label, **never blank** (falls back to identifier).
- `name` and `qid` are **reserved identity concepts, not data fields.**

## 2. Class kinds

`ClassKind` states how a class is constructed and therefore where its identity
comes from:

- **SOURCE** — populated from a datasource and identified by that source's id.
- **STATEMENT** — reified from statements and identified by its declared natural
  key (or a surrogate when no key is configured).
- **OWNED** — produced at a field site and identified structurally by owner +
  production site. A borrowed source id is only an acquisition address.

## 3. Identity + displayName rules

| Kind | Identity | displayName |
|------|----------|-------------|
| SOURCE | datasource id | Source **Label**, a **Field**, or a **Template** |
| STATEMENT | **natural key** = ordered key fields (default: the reification **grain**) | Label fallback, **Field**, or **Template** |
| OWNED | owner identity + production site | Label fallback, **Field**, or **Template** |

- **Field-mode displayName and identity key fields require `SINGLE` cardinality.**
  A single reference resolves via the referent's displayName. Combining
  `COLLECTION` values into a label is **Template-only** (with an explicit join).

## 4. Reserved names

No data field may be named `name`/`qid`. On add/import the builder **auto-renames**
(e.g. a native-name property → `nativeName`) with a visible warning — not a hard
reject.

## 5. Model representation (engine)

- `ClassKind` on `GeneratedClassModel` is the sole identity discriminator.
- `CanonicalSpec` contains the policies used by that regime:
  `{ keyFields[], displayNameMode = LABEL|FIELD|TEMPLATE,
     displayNameField, displayNameTemplate, labelLanguage }`.
- `getIdentifier()`/`getDisplayName()` on the materialized object are computed
  from `CanonicalSpec`.
- Remove `ensureNameField()` and the implicit `name` field; drop the
  `"name".equalsIgnoreCase` `isNameField()` heuristic (identity becomes explicit).
- **Back-compat:** load removes the retired `CanonicalSpec.kind` from old files;
  construction determines the regime. Missing canonical specs are repaired to
  defaults, and statement defaults are materialized by the statement editor.

## 6. Sort / search / config

`ViewableFieldPaths` surfaces the displayName **once** and identity **once**
(deduped). No model field can double or compete with them.

- **Surface title** stays `name` (back-compat with existing field paths like
  `["episodes","name"]`).

## 7. UI (kind-aware "Identity & label" section per class)

- SOURCE → source id; displayName may remain the source label or be configured as
  Field/Template.
- STATEMENT → identity key fields (prefilled from grain, editable).
- OWNED → owner + production site; it does not expose canonical-key editing.
- DisplayName is independent of identity for all three kinds; Field dropdowns
  offer only `SINGLE`-cardinality fields.
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
