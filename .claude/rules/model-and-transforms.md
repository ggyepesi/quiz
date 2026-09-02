---
description: Declared roles, carrier shapes, identity, and the order a generation must run in
paths:
  - "app/src/main/java/wikidata/explore/model/**/*.java"
  - "app/src/main/java/wikidata/explore/transform/**/*.java"
  - "app/src/main/java/wikidata/explore/generation/**/*.java"
  - "app/src/main/java/wikidata/explore/extract/**/*.java"
  - "objectview/src/main/java/objectview/field/**/*.java"
---

# No name-based special-casing

**No code may special-case a field or class by its literal NAME** (`"qid"`, `"name"`, `"id"`,
`"source"`, `"record"`, …). Any special role — identity, display title, provenance, link,
entity-vs-value — is declared EXPLICITLY and read through a generic public mechanism: an
annotation, a contract method, or a declaration API for dynamic objects.

Name inference has bitten this project repeatedly (Oscar fields, then the provenance `source`
field, whose `@Link` field was named `qid` and so was treated as a Wikidata entity — a phantom
`source.qid` path, coverage reading 0.0, one object showing three different sub-field names).
Renaming only moved the collision. The bug class disappears only when no layer keys on names.

Identity and display come from the `Viewable` contract (`getIdentifier()` / `getDisplayName()`),
never from a field called `qid` or `name`. `NameBasedRoleGuardTest` fails on new violations;
add to its allowlist only together with a declared-role replacement.

# Carriers are siblings, never subclasses

**No `XDynamicObject extends YDynamicObject`.** A statement carrier must not extend the entity
carrier — it would inherit qid/entity identity semantics a statement does not have. Entities
identify by QID, statements by GUID plus property: different *shapes*, not a specialization.

Carriers are siblings over a neutral, source-agnostic base, each composing its own identity
shape. Provenance is a **field descriptor** on the carrier (`X.source : WikidataViewable`),
swappable to re-anchor X — not an inherited base class.

# Identity

Object identity is ⟨typeKey, id⟩, not name — that is what stops a State and a Group both called
"France" from merging, and it acts at SAVE time. A VALUE object has no id and is inlined; an
ENTITY has an id, is pooled and referenced. `@Inline` is the explicit opt-in.

# Admission is not representation

An evidence rule such as `Person: P31 = Q5` declares non-exclusive membership admission. It
must never globally choose the carrier used by a consuming role. That choice is an explicit,
ordered contextual representation on the role (`Laureate -> Person`); importing or inspecting
an admission alone performs no retyping. See `docs/contextual-entity-representation.md`.
An authored subclass inherits its base's admission, including across a model import; it does
not duplicate that declaration merely to participate in contextual representation.

# Generation order

The pipeline's order encodes real dependencies. Changing it means checking these:

1. Load the declared fields of role members **first** — that evidence is what classification
   reads, and re-fetching it is a second download of the same claims.
2. Classify entity kinds from stored evidence, then fetch remotely only for candidates that had
   **no** stored evidence.
3. Build owned components **after** kinds are settled: a Nominee must already BE a Person before
   `Person.structuredName` can be produced for it.
4. Run a second field-load pass for what the new kinds and parts declare, skipping declarations
   the first pass covered — by exact covered QIDs, never by count, because a count cannot see
   one entity replace another.
5. Canonicalize and prune dead stubs **after** acquisition, so "not fetched yet" is never
   mistaken for "dead".

An owned class is produced per owning instance on the OWNER's QID; being owned is a property of
the CLASS, where it is produced is a property of the FIELD (the production site is part of the
type key). A part is never a served type, and its name is composed as owner + site — never the
owner's own label, which its own fields can contradict.

Remap re-runs the pure transforms offline in the same order. Enrich is additive and works in
place, checkpointed by exact covered QIDs.

# SPARQL

Generated queries follow `docs/sparql-generation-rules.md` (R1–R18): two-sided VALUES, a
membership backbone, grouped SAMPLE/GROUP_CONCAT with inline labels instead of a flat LIMIT, and
awareness that WDQS can answer a partial result as a silent 200. A query that drifts in count
between runs is usually violating one of them.
