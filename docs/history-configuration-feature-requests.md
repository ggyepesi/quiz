# History configuration feature requests

> **Status: History is PARKED (26 August 2026) — these requests are not.** They were
> found by configuring a real domain and each cost real time in ModelBuilder, so they
> stand on their own merits.
>
> Shipped since:
>
> - **#4 tabbed hit navigation** — `SearchControlsTabs`, one bounded scrollable area
>   holding each class's navigator behind its own tab, in objectview's coordinated
>   search exactly as the request asked.
> - **#5, the preview half** — "Copy class…" shows the dependency closure, the
>   supporting declarations and the conflicts before anything is applied, and the
>   import is all-or-nothing. *Refreshing* an already-copied class is still open.
>
> Still open:
>
> - **#1 copy a FIELD configuration.** Class copying between domains shipped; copying
>   one field's source/property within a class did not, and is the more frequent need.
> - **#2 load a model from its domain directory.** The Load chooser still opens at the
>   data root. The log window learned this habit; the model chooser has not.
> - **#3 unsaved-change tracking.** There is a discard prompt on domain switch, but no
>   clean-baseline tracking and no dirty indicator in the title.

This is the working list of needs discovered while configuring the History domain.
Each request starts from a concrete configuration task; implementation and broader
generalization can be decided after the pattern has been observed.

## 1. Copy a field configuration

**Status:** requested

**History motivation:** `Person.birthDate` and `Person.deathDate` have the same field
shape and presentation. After configuring `birthDate`, creating `deathDate` should
require changing only the field name/display name and the Wikidata property from P569
to P570.

**Desired behavior:** From a configured field, offer **Copy field…**. The copy should
start as a complete independent duplicate, including:

- value type, cardinality and field production settings;
- source binding and property mapping;
- search, sort and view metadata;
- required/expected status and other field-level rules.

Before committing, ask for the new field name and allow the copied source/property
mapping to be edited. The original and copy must not share mutable configuration.

**Why this is general:** Many domain fields differ only in relation/property—for
example birth/death dates, start/end dates, parent/child roles, or origin/destination.
Copying reduces repetitive configuration and prevents subtle drift between fields
that intentionally share a shape.

## 2. Open Load saved in the domain directory

**Status:** requested

**History motivation:** Loading a saved History snapshot currently requires navigating
back to its directory manually, even though ModelBuilder already knows the selected
domain/project and its snapshot location.

**Desired behavior:** **Load saved…** should open its file chooser in the current
domain's snapshot directory. If there is no current domain, use the most recently used
domain directory; only then fall back to the application's general project/data
location.

The default is navigation assistance only: the user must still be free to choose a
snapshot elsewhere, and cancelling must not change the remembered location or current
domain.

## 3. Track whether the current domain has unsaved changes

**Status:** requested

**History motivation:** Switching between History and an existing domain currently
shows a save/confirmation dialog even when the current domain has not changed. During
iterative configuration this adds friction and makes the warning less meaningful.

**Desired behavior:** ModelBuilder should maintain a dirty bit for the current domain.
Opening, loading or successfully saving a domain establishes a clean baseline. Any
user-visible model/configuration change marks it dirty; reverting to the saved state
may mark it clean again if structural comparison is practical.

When switching domains, closing the application, or replacing the current model:

- if clean, continue without a save dialog;
- if dirty, offer Save / Discard / Cancel;
- a failed or cancelled save leaves the domain dirty and aborts the switch;
- generation progress, selection, window layout and other transient UI state must not
  mark the domain dirty.

The window title should show a small conventional dirty indicator so the user can see
the state before attempting to switch.

## 4. Tabbed hit navigation for multi-class instance views

**Status:** requested

**History motivation:** A multi-class **Show instances** view has one appropriate
global search/configuration surface, but a search can produce separate hit navigators
for several class panels. Stacking those navigators vertically is clumsy, consumes too
much of the card viewport and makes it harder to see which class is being navigated.

**Desired behavior:** Keep the search input and Search/Sort/View configuration global.
When a query is active, present each class's hit navigator as a tab in one bounded,
scrollable navigation area rather than as vertically stacked panels.

- Tab captions should contain the class name and hit count.
- Classes with no hits should either be omitted or clearly disabled.
- Selecting a result in a tab should focus the corresponding card in that class's
  virtualized instance panel.
- Previous/next navigation stays scoped to the selected class tab; an optional global
  next/previous action may traverse tabs in display order.
- Clearing the query hides the complete hit-navigation area and returns all vertical
  space to the instance panels.

This belongs in objectview's coordinated multi-view search rather than in ModelBuilder,
so every multi-panel consumer receives the same interaction.

## 5. Replace or refresh a copied root class

**Status:** requested

**History motivation:** `Person` was copied from Oscar Nominations into History and
became History's root class. After adding `deathDate` to the source class, repeating
the copy is blocked because the existing root class cannot be removed.

**Desired behavior:** Copy Class should recognize that the destination already contains
the class and offer an explicit update workflow:

- **Replace from source** — replace the destination class configuration while keeping
  it bound as the destination domain's root;
- **Merge missing changes** — bring in new fields such as `deathDate` without
  overwriting destination-specific edits;
- **Cancel** — leave the destination untouched.

The preview should list fields, rules and dependencies that will be added, replaced or
kept. Referencing fields and root bindings must remain valid atomically; a failed copy
must leave the old class intact.

Removing a root class may still be useful, but it should require selecting a replacement
root or explicitly clearing the domain. The model should not pass through a silently
invalid rootless state merely to support re-importing a class.
