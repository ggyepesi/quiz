# Model Builder — Qualifier Field Cheat‑Sheet

Adding a **qualifier‑sourced field** to a reified statement class (e.g. giving
`Nomination` a `year`, `forWork`, or `edition` field). This is the part the main
`modelbuilder-guide.txt` doesn't cover. Print this; it's stop/start‑safe.

Mental model: a reified class (one with **Reify from** set) turns each statement
of a property (Oscar `Nomination` = each `nominated for` / **P1411** statement)
into a record. Its fields come from the statement's **qualifiers**:
`year ← P585`, `forWork ← P1686`, `edition ← P805` — all siblings on the same
statement.

---

## Window map (only what you need here)

- **Left — "Domain & Classes"**: the class tree + buttons
  `Rename class | Add class | Add field | Suggest facets | Remove`.
- **Right — "Configuration"**: editor for whatever you select —
  - select a **reified class** → the **"Statement class"** panel (read‑only
    derived view: *Identity / Roles / Dedup key / Qualifier fields*).
  - select a **field** → the **field editor**.
- **Toolbar**: `Generate class instances` (selected class) · `Generate domain` (all
  classes) · `Remap (no download)` · `Save domain` · `Show rule tree` ·
  `Show query logs` · `Depth`.

---

## Steps — add a qualifier field

1. **Left tree → select the reified class** (e.g. `Nomination`).
   Glance at the **Statement class** panel on the right: *Statement property*
   should be the reified property (P1411), and *Qualifier fields* lists what's
   already mapped (`year (P585)`, `forWork (P1686)`, …).
2. Click **`Add field`** (adds an AUTO field to that class).
3. In the **field editor** (right), set:
   | Row | Set to | Note |
   |---|---|---|
   | **Field name:** | `edition` | becomes the JSON key |
   | **Holds:** | `Entity` | (or Date/String/Number for a scalar qualifier) |
   | **Of class:** | `Edition` | pick **`New class...`** to create it; Entity only |
   | **Count:** | `Single` | (Collection for a multi‑valued qualifier) |
   | **Qualifier of:** | `P805` | ← **the key** — this makes it a qualifier field |
   | **Property:** | *(leave blank)* | qualifier fields don't use the statement value |
   | **Found on:** | *(leave default)* | not used for qualifiers |
4. Click **`Apply field source`** (watch for the confirmation next to the button).
5. **Verify** — re‑select the class; the **Statement class** panel should now
   list your field under **Qualifier fields**. Also read **Roles** and
   **Dedup key** (see the gotcha below).

---

## Regenerate

- A new qualifier is **new network data** → use **`Generate domain`** (full
  re‑download). **Not** `Remap` — that re‑runs transforms on the *cached* pool
  and won't fetch the new PID.
- Set **Depth ≥ 1** if the qualifier's target class has its own fields to load
  (see the optional year step below).
- **`Save domain`** → writes `model.json` / `ruletree.json` / `snapshot.json`
  under `data/wikidata/<key>/`.

---

## ⚠ Gotcha — entity qualifiers become ROLES + identity‑key members

The reifier (`ModelStatementReifications`) treats a **single‑ENTITY** qualifier
as a **role** *and* puts it in the **dedup identity key** — that's correct for
`forWork` (it defines the atom). A **DATE** qualifier (`year`) is deliberately
kept **out** of the key (it's a shared *attribute*, often missing on one copy).

An entity qualifier that is really an **attribute** — like the ceremony
**edition** (same for every nominee of a ceremony) — will therefore land in the
identity key. If its PID (`P805`) is absent on one denormalized copy (the
person‑side vs work‑side statement), those copies **split into duplicates**.

- **Check after regen**: the `Nomination` count, and whether `edition` shows in
  **Roles / Dedup key** in the Statement class panel.
- **Clean fix (code)**: treat entity‑*attribute* qualifiers like the DATE `year`
  — keep them out of the roles + key. Small `ModelStatementReifications` tweak —
  ask Claude if duplicates appear.

---

## Worked example — `edition` on `Nomination`

`statement is subject of` (**P805**) → the ceremony (`98th Academy Awards`),
which carries a date → the year. It's a sibling qualifier to `forWork` (P1686),
covers **99.5%** of nominations (winners *and* losers), and is a real dimension
(facet/group by ceremony).

- Field: `edition` · Holds `Entity` · Of class `Edition` (new) · Count `Single`
  · Qualifier of `P805` → **Apply** → **Generate domain** (Depth 1) →
  **Save domain**.
- **Fill `year` from the edition (optional, same pass)**: on the new `Edition`
  class, **Add field** `date` · Holds `Date` · Property `P585`, and generate at
  **Depth ≥ 1** so the ceremony's own date loads. Then `year` can derive from
  `edition.date`. Without this, `edition` arrives as a bare `"98th Academy
  Awards"` reference — the dimension only, no year yet.

Qualifier PID reference: `year = P585` · `forWork = P1686` · `edition = P805`
(all on the same `nominated for` (P1411) / `award received` (P166) statement).
