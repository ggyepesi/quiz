# Making Configuration Concise

## Status

**First step implemented** (`d661438b`, then hardened): a plain string field is asked
15 rows instead of 23. Written after measuring the configuration surface rather than
from the impression that it had grown — and corrected below where the first measurement
was wrong. The hardening matters: visibility now asks the complete compiler rule (including
that an entity target is a modeled class), Apply asks that same rule, and rows react to
unsaved type changes rather than lagging until a field is applied and reopened.

## The problem, measured

| editor | controls | top-level rows | rows a reader sees | lines |
|---|---|---|---|---|
| `FieldSourcePanel` | 25 | 15 | **23** | 1665 |
| `ClassSourcePanel` | 26 | 29 | — | 1356 |
| `StatementSourcePanel` | 8 | 21 | — | 749 |

The first count of this note said fifteen. That was the number of top-level
`labeledRow` calls, not the number a reader sees: the shared field-definition panel adds
five more and the qualifier settings three, which a grep for one call shape does not
find. The true figure came from a failing test printing the whole visible set — the
measurement taken by accident was better than the one taken on purpose.

Configuring one plain string field showed twenty-three labelled rows:

```text
Field name · Holds · Of class · Count · Display · Load as · Inverse of ·
Graph expansion · From · Property · Value language · Wikipedia fallback ·
Wikipedia categories · Qualifier of · Qualifier time · Missing qualifier ·
Reify role · Subject field · Match value field · Match role field ·
Found on · Expectation · Numeric filter
```

Most cannot apply together. `Inverse of` is meaningful only for an INVERT field;
`Subject field` / `Match value field` / `Match role field` belong to companion-match and
date-projection fields; `Graph expansion` needs a typed entity field with a property.
Someone configuring a plain string field is shown `Match role field` and `Inverse of` —
controls that can never do anything for them.

No individual knob is wrong. Each arrived with a forcing reason, and the working
agreement requires every generation knob to be reachable in the UI. What was never
decided is that they should all be visible *at once*, whatever is being configured.

## Why it is like this

`FieldSourcePanel` has **22 `setEnabled` calls and 3 `setVisible`**. That is not a
preference for greying. Every row is built as

```java
GridBagUtils.labeledRow(form, c, y++, "Load as:", productionBox);
```

The label is a string literal handed into the layout, so **nothing holds the `JLabel`**.
Hiding a control leaves its label orphaned beside empty space. Greying was the only
option the row helper left available.

`GridBagUtils` already has the overload that takes a pre-built `JLabel`. Nothing in
objectview needs to change.

## Three states, not two

A blanket "hide what does not apply" would be wrong. The code already records a case
where visibility carries meaning:

```java
// A qualifier source is meaningful only on a StatementClass. The field
// remains visible so the class/field relationship is explicit.
qualifierPidField.setEnabled(statementClass);
```

So the distinction to implement is:

- **Irrelevant here** — hide. The control cannot apply to a field of this kind, and its
  presence is noise.
- **Relevant but unavailable** — grey, and say why. Seeing it teaches what the field
  could become.
- **Locked** — grey. A run is in progress or the model is read-only; this is about
  permission, not applicability.

Today all three are greyed, which is why the panel reads as uniformly complicated.

## The plan

1. `FormRow` — a label/control pair that hides as a unit, built on the `JLabel` overload
   that already exists. No objectview change.
2. Convert the rows whose relevance rule **already exists** in the panel, taking each
   condition from where it is written today rather than inventing one:

   | row | existing rule |
   |---|---|
   | Inverse of | `productionKind == INVERT` |
   | Graph expansion | typed entity target + SPARQL property source |
   | Subject field | companion match, or date projection |
   | Match value field | companion match, or date projection |
   | Match role field | companion match |
   | Qualifier time | `dateQualifier` |
   | Missing qualifier | `policyEnabled` |
   | Reify role | `policyEnabled` |
   | Qualifier of | statement class — **stays greyed**, per the comment above |

   Eight rows, not the five first listed here: `Qualifier time`, `Missing qualifier` and
   `Reify role` have rules too and were missed for the same reason the row count was
   wrong — they are not written in the call shape that was grepped. That takes an
   ordinary field from **23 rows to 15**.

   The remaining rows — `Numeric filter` on non-numeric fields, the Wikipedia rows on
   non-Wikipedia sources — have **no rule written anywhere today**, and inventing one in
   the UI is how the editor drifts from what actually compiles. They wait for a rule to
   exist, and are then one line each.
3. Ask the shared predicate. `refreshGraphExpansionControl`, Apply, validation and
   compilation must not each define “traversable.” The shared predicate therefore has
   both saved-field and parts-based forms: the editor asks it from live controls, while
   Apply and the compiler ask it from the field. “Typed target” includes a class that is
   actually present in the model; a vocabulary selection is entity-shaped but is not a
   graph-expansion class.
4. Test both snapshots and transitions. “Given a field of kind X, exactly these rows are
   visible” catches the initial layout. “Change X to Y without Apply, then back” catches
   the equally important live-editor contract. Positive cases are required too: otherwise
   a permanently hidden row satisfies every negative visibility assertion.

Size, as built: about 200 lines across `FormRow`, the panel and the shared predicate.
Contained, as expected — `GridBagLayout` collapses a row whose components are all
invisible, so the running `y++` counters needed no rework.

## What this is deliberately not

**Not a unification of argument passing.** That was considered as the first step and the
measurements say it lands elsewhere: `FieldSourcePanel` — the most crowded editor —
contains **zero** hand-off buttons. Its 25 controls are 17 combo boxes, 5 text fields, 2
checkboxes, 1 spinner; only `propertyPid` and `qualifierPid` are the kind a tool could
fill. Hand-offs live in the tool panels, crowding lives in the editors, and the two
barely intersect.

Typed hand-off subjects are still worth doing, for a different reason: six hand-offs
share `BiConsumer<String, String>` across four meanings — `(pid, label)`, `(pid, qid)`,
`(qid, label)`, `(class, field)` — so mis-wiring compiles silently. That is a
correctness fix, and it declutters nothing.

**Not a routing registry.** Destinations declaring what they accept, with action rows
generated from them, would fix the visible duplication in the tool panels — three
copies of "Explore entity relation", two of "Add selected to Seed QIDs", and labels that
have drifted into "Use" / "Use selected" / "Use selected as class type (P31)". It is
also 600–800 lines across eleven panels, **eight of which have no test at all**. Worth
doing when a concrete need forces it; not before the release.

**Not new rules.** The relevance conditions must be the ones validation already uses,
asked — never a second set written for the editor. A copied rule drifts, and this
codebase produces that failure more reliably than any other.

## Cheaper than any of it

One constant per action label removes accidental wording drift only where the actions
really have the same meaning. “Use selected” and “Use selected as class type (P31)” may
look related while promising different model changes; collapsing those labels would make
the UI terser but less truthful. The cheaper safe step is a small vocabulary of shared
verbs plus destination-specific objects, with each label still describing its effect.

## Review conclusions for the next slice

- Continue converting only rows whose applicability is owned by a model/compiler rule.
  Do not infer relevance from control labels, datasource names or class names.
- Treat the editor as a live model draft. Every dependency (`Type`, `Load as`, target
  class, source and property) must refresh affected rows before Apply.
- Keep **relevant but unavailable** visible with a reason. Hiding it would erase the route
  by which a reader learns how to make it available.
- Measure the next editor before changing it. `ClassSourcePanel` has the larger surface,
  but its controls describe several identity/source regimes; row count alone does not
  identify which sections can safely disappear.
- Prefer progressive sections over a second dialog. A concise editor should preserve the
  configured model in one place, not move advanced settings into a parallel editor with
  a second save boundary.

## Deferred until the Nobel walkthrough is complete

The Nobel configuration repeatedly leaves controls untouched: Alias, Extends,
Subtype, Wikidata type/class membership, Reifies statements of, and several source
details remain visible even when the selected construct does not need them. Revisit
this after the walkthrough, using its complete friction list rather than redesigning
mid-demo.

The likely direction is construct-specific progressive disclosure:

- the chosen class/field kind determines the small required surface;
- relevant optional controls remain discoverable in named sections;
- uncommon cross-cutting controls live under an explicit **Advanced** section;
- an existing non-default value keeps its section visibly marked or expanded, so
  simplification never hides configured state;
- visibility asks the same applicability predicates used by validation and compilation,
  rather than a UI-only table of class names or labels.

Whether the current basic kinds need refinement is intentionally left open. First
measure which controls Nobel actually needed for Source, Statement, role/entity and
qualifier fields; only then decide whether progressive sections are sufficient or the
type vocabulary itself is overloaded.
