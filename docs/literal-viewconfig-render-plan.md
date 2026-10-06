# Literal ViewConfig rendering

Status: **approved (2026-10-06). Phases 1–5 done, and phase 6's editor boundary: the
editor desugars configs where they enter and emits literal ones; the table's columns are
the literal leaf paths. Remaining in phase 6: confirm Apply rebuilds visible cards.
Next: phase 7 (web).**

> A field is rendered if and only if it is ticked. Collections and DISPLAY have a
> few specific rules (§2). Nothing else decides.

## 1. Why it failed: shorthand interpreted everywhere

Phase 0 ran TransformApp's Show instances route on real History data (Wigmund of
Mercia). It found two things.

- **The config is not a list of ticks.** `allFields`, `allMinorFields`, an absent
  child config and the `@view:display` alias are shorthands. They were never
  rewritten into ticks. Instead the editor, Card, ValueRenderer, the table, the web
  serializer and quiz extraction each interpreted them, each with its own fallback.
  The editor's default config pre-ticked `offices.source`, `offices.position` and
  three fields under position, none of them chosen by the user. So `source` rendered
  (as an "Open" link, its own DISPLAY being unticked). The renderer obeyed a config
  the user never wrote.
- **Edits did not reach the visible card.** After ticking
  `offices.position.Display label`, the card on screen kept the config it was built
  with. This was observed without pressing the dialog's Apply, so it is not yet
  established as a separate defect; phase 6 checks it with the editor on the same
  harness.

The cure is directive 24 (modularity): rewrite every shorthand into plain ticks
once, where it enters, and give every other decision exactly one owner.

## 2. The rules (unchanged, from CLAUDE.md)

1. A ticked field is rendered; an unticked field is neither rendered nor read.
2. A `null` value renders nothing.
3. A ticked object field with nothing ticked under it renders its field name only.
4. DISPLAY is an ordinary field. When ticked, its value is painted once, as the
   object's caption, and is not repeated as a body row. When unticked there is no
   caption and no fallback through `getName`, `getDisplayName` or a reference label.
5. A ticked collection renders `field name (size)` at once, including size 0. Its
   members are built only when it is expanded, and each member follows the
   collection's member config.
6. Initial disclosure is presentation, not selection. Ordinary collections start
   folded; singleton media and `@Inline` content start open; a nested object starts
   open.
7. A scalar reference whose target has its own top-level card is a navigation link.
   Its caption is the target's ticked DISPLAY, else "Open". Members of an expanded
   collection are that collection's projection even when they also have cards.
8. An object already on the current path renders as a back-reference, never again.
9. Selecting an object field never selects DISPLAY or any other child implicitly. An
   object implicitly included by "all fields" has nothing ticked under it.
10. The IDENTITY role is never a body field.
11. Structural fields are never included by "all fields"; only an explicit tick shows
    them.

## 3. Stages and their single owners

```text
schema ──> defaults ──> desugar ──> literal ViewConfig ──> resolve ──> plan ──> execute ──> sink
```

Every decision has exactly one owner. No later stage re-decides an earlier one.

| Decision | Owner | Nobody else |
|---|---|---|
| Which fields a logical type has; their kind, role and hints | `FieldSchema` / `FieldSet` (exists) | infers fields from a name or a Java carrier class |
| The ticks of a new config | `ViewDefaults` | invents ticks while rendering or editing |
| Expanding `allFields`, `allMinorFields`, absent children and the DISPLAY alias into ticks | `ViewConfigDesugar` | reads those flags |
| How a ticked field is represented (caption, text, link, media, object, reference, collection) | `PlanResolver` | chooses a representation |
| Presence, disclosure, cycles, navigation, caption placement | `RenderExecutor` (+ `DisclosurePolicy` for the initial state) | skips, folds or replaces content |
| Pixels / JSON / trace | sinks: Card, table, web, mock | decides anything |

### 3.1 Literal ViewConfig

A literal ViewConfig has `allFields = false` and `allMinorFields = false` at every
level. Its field map lists exactly the ticked fields, under their real names (never
the `@view:display` alias). Each ticked object or object-collection field has its
own literal child config, which may be empty. Absence means unticked; that is the
plain reading of a list of ticks, not a shorthand. A literal config is finite,
because an object implicitly included by "all fields" gets an empty child (rule 9).

### 3.2 `ViewConfigDesugar` — the only reader of shorthand

`literal(config, schema)` turns any config into its literal form against the schema
it will render with. It runs at the boundaries where a config enters:

- creation (`ViewDefaults`, and every `ViewConfig.all(...)` call site);
- load (saved configs, `ConfigState`);
- edit (the editor emits literal configs).

After phase 9 nothing else reads `isAllFields`, `isAllMinorFields` or the alias.
`ConfiguredFieldSelection` becomes its private rule.

### 3.3 `ViewDefaults` — one default

A new View config ticks every top-level field (minor ones included) and nothing
below them (rule 9). It is produced already literal. The editor, Card, the table and
the web no longer create defaults of their own.

### 3.4 `PlanResolver`

`resolve(literalConfig, fieldSet)` returns the `ObjectPlan` for one object level:

- the caption field (the DISPLAY field, if ticked);
- the ticked body fields in config order, each with its representation and its
  exact child config;
- the unticked fields, kept for diagnostics only.

The representation comes from schema hints in one function:

- DISPLAY becomes `CAPTION`;
- a collection or map becomes `COLLECTION`;
- an embedded field becomes `OBJECT`;
- a reference becomes `REFERENCE`;
- media becomes `MEDIA`, `@Link` becomes `LINK`;
- otherwise `TEXT`.

A field the schema cannot classify (a dynamic field with no schema) becomes `VALUE`.
The executor resolves `VALUE` by the value's shape in one function, `ValueShape`.
Plans are memoized per (config, logical type).

### 3.5 `RenderExecutor`

The one loop. For each field plan of an object:

- **Unticked:** `SKIP(OFF)` without reading the value.
- **Absent:** reads it; `SKIP(ABSENT)` if `null`.
- **Caption:** emitted once, before the body.
- **Object:**
  - an ancestor gives `BACK_REFERENCE` (rule 8);
  - a scalar `REFERENCE` to a top-level target gives `NAVIGATION` (rule 7);
  - otherwise the object opens, and its child plan runs per `DisclosurePolicy` and
    the reader's toggles.
- **Collection:** `label (size)`; members are executed only if expanded, otherwise
  `DEFER(COLLAPSED)` and no member is read.

Sinks receive these calls and paint them; they have no other inputs.

## 4. The mock harness: the primary contract test

The mock sink is an ordinary sink of the same executor. It builds no Swing
components. It records the decision trace and a tree of mock components: `MockObject` (which
carries its caption), `MockCollection`, `MockNavigation`, `MockBackReference` and
`MockLeaf` (text, link or media). Disclosure is driven through `MockDisclosure`:
expand, collapse and re-expand re-execute the same plan.

Fixtures are read-probed. A field read outside the permitted list fails the test:
the IDENTITY role, plus a collection's size while its members are deferred. So
"read it and then hide it" is caught, not just "painted it".

Tests state the complete expected trace, for example:

```text
CAPTION    Wigmund of Mercia
COLLECTION offices (2) expanded
  OBJECT   offices[0]
    SKIP   offices[0].source            OFF
    OBJECT offices[0].position
      CAPTION King of Mercia
  OBJECT   offices[1]
    SKIP   offices[1].source            OFF
    OBJECT offices[1].position
      CAPTION monarch
```

The matrix covers:

- every representation ticked and unticked, plus `null` values and empty collections;
- DISPLAY at every level, and rule 3 shells;
- navigation versus inline, and back-references;
- dynamic versus reflected backing, and a Java carrier shared by several logical
  types;
- initial disclosure, expand, collapse, re-expand, and independent siblings.

A new representation needs its mock counterpart and matrix cases before any Swing or
web sink.

Tests are not rewritten because an implementation paints something different.
Changing an expected trace is a design decision first.

## 5. Phases

Each phase ends in tests and a reviewable commit. No phase adds a fallback.

1. **Desugar and defaults.** `ViewConfigDesugar`, `ViewDefaults`, literal-config
   tests (all-fields, minor, alias, absent children, structural, nested).
2. **Resolver and plan.** `PlanResolver`, `ObjectPlan`, `FieldPlan`,
   `Representation`, printable plans.
3. **Executor and mock harness.** `RenderExecutor`, `DisclosurePolicy`, `ValueShape`,
   the sink interface, mock sinks, read-probed fixtures, the full trace matrix and the
   History-shaped regression.
4. **Card becomes a sink.** Delete Card's own selection, default and representation
   paths: `shows`, `defaultConfigForValue`, `configForNested`,
   `ObjectOccurrencePlan`, and ValueRenderer's object and collection branches.
5. **Table becomes a sink**, sharing paths for search highlighting.
6. **Editor at the boundary.** The editor displays and emits literal configs from
   `ViewDefaults`/`ViewConfigDesugar`; applying a config rebuilds visible cards from
   it. Then re-run the phase-0 route and record the outcome here.
7. **Web becomes a sink.** An entity fetched later by id carries its field's config
   path.
8. **Quiz** renders through the same plan; key extraction reads literal configs.
9. **Cleanup.** No caller of `isAllFields`, `isAllMinorFields` or the alias remains
   outside `ViewConfigDesugar`; delete obsolete APIs.

Between phases 4 and 8, migrated consumers execute the plan and unmigrated ones keep
their current paths. Each migrated consumer has no fallback.

## 6. Open

- `typeConfigReference` (a child that means "this type's config"), set aside in a git
  stash on 2026-10-06, is a new shorthand. Under directive 24 it must either be desugared at the boundary
  or not exist. For a recursive type it cannot be desugared finitely. Literal configs
  need no recursion, because of rule 9, so the plan does not adopt it.
- The uncommitted `CLAUDE.md` text says that navigation versus inline is a per-field
  config choice, and that defaults tick DISPLAY for nested objects. Both differ from
  rules 7 and 9. They stay as committed until decided.
