# StatementClass — explicit field roles (refactor step b)

## Context

A **StatementClass** (e.g. `Nomination`) reifies a source class's statements
(`OscarNominations`' P1411) into records. Each of its runtime fields plays a
**role** in that reification. Today those roles are *mostly* explicit config, read
by `ModelStatementReifications` when it derives the `QualifierLoadConfig`:

| role | how it's set today | explicit? |
|---|---|---|
| **qualifier** | `FieldSourceMapping.qualifierPid` (blank = not a qualifier) | yes |
| **dedup key** | `inDedupKey` / `CanonicalSpec.keyFields()` | yes |
| **companion subject** | `subjectField` / `matchValueField` / `matchRoleField` | yes |
| **missing-qualifier fallback** | `missingQualifierPolicy` | yes |
| **value** (the statement's main value, `ps:<pid>`) | **inferred by `findValueField`** | **no** |

`findValueField` (both the editable- and compiled-model paths) does:
1. the first runtime, non-qualifier field whose `propertyPid == statementPid` — the
   correct, explicit link; else
2. **the first runtime non-qualifier field** — a guess; else
3. the literal string `"value"`.

Step 2 is the inference-failure gotcha: a misconfigured class silently reifies the
wrong field as its value (wrong `category`, wrong dedup, wrong counts), instead of
telling the modeller the value field is missing.

## Goal

Make the **value role explicit and orthogonal** to the others — a single predicate
in `StatementFieldSemantics`, used by both derivation paths and the validator — so
the reify *looks it up* and a missing value field is a loud validation error, not a
silent wrong guess.

## Design

- **`StatementFieldSemantics.statementValueField(owner)`** — returns the field that
  plays the value role: the runtime, non-qualifier field whose normalized property
  equals the class's statement-source PID (`owner.statementSource().propertyPid()`).
  Returns the field (or its name), or empty when none — **no first-field guess**.
  Orthogonal to `isQualifierField`: value ⇒ not qualifier, by construction.
- **`ModelStatementReifications.findValueField`** (editable + compiled) → delegate to
  the predicate; drop the "first non-qualifier field" fallback. Keep `"value"` only
  as the truly-empty default (a class with no fields at all), which the validator
  already rejects.
- **Validator** — a `reifiesStatements()` class with runtime fields but no
  value-role field is flagged (structural error, like the existing forWork/source
  checks), so the misconfiguration surfaces at save/compile, not at reify time.
- **Parity** — `OscarReifyTest` (13) must stay green; `Nomination.category`
  (P1411 == statement PID) resolves identically. Add tests: value resolved by
  PID-match; ambiguous/missing surfaced rather than guessed.

## Step a — resolve the value role at compile (DONE)

`CompiledStatementSource` now carries a `valueField`, resolved ONCE by
`ProjectModelCompiler` via `StatementFieldSemantics.statementValueFieldName(clazz)`.
The compiled reify path reads `statementSource.valueField()` instead of re-deriving
it (`findValueField` kept only as a fallback for a compiled model that predates the
field). So the value role has a single source of truth threaded through the compile —
the reification analog of the extraction compile. (`ProjectModelCompilerTest.`
`compiledStatementSourceCarriesTheResolvedValueField`.)

Later (not yet): carry the resolved qualifiers + dedup + valueQids + fallback roles on
the compiled source too, so the *whole* `QualifierLoadConfig` is compile-derived, not
just the value field. They're already read from the compiled fields at reify time, so
this is a consolidation, not a correctness fix.

## Out of scope

- A dedicated `statementValue` boolean flag on the field (the PID-match *is* the
  explicit link; a flag would be redundant). Revisit only if a class legitimately
  needs a value field whose property differs from the statement PID.
- The companion-match / won role (already explicit; excluded from the canonical key).
