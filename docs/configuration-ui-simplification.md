# Making Configuration Concise

## Status

Design note, not yet implemented. Written after measuring the configuration surface
rather than from the impression that it had grown.

## The problem, measured

| editor | controls | labelled rows | lines |
|---|---|---|---|
| `FieldSourcePanel` | 25 | 15 | 1665 |
| `ClassSourcePanel` | 26 | 29 | 1356 |
| `StatementSourcePanel` | 8 | 21 | 749 |

Configuring one field shows fifteen labelled rows:

```text
Load as · Inverse of · Graph expansion · From · Property · Value language ·
Wikipedia fallback · Wikipedia categories · Qualifier of · Subject field ·
Match value field · Match role field · Found on · Expectation · Numeric filter
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
   | Qualifier of | statement class — **stays greyed**, per the comment above |

   That takes an ordinary field from fifteen rows to about ten. The remaining rows —
   `Numeric filter` on non-numeric fields, the Wikipedia rows on non-Wikipedia sources —
   have **no rule written anywhere today**, and inventing one in the UI is how the
   editor drifts from what actually compiles. They wait for a rule to exist.
3. Ask the shared predicate. `refreshGraphExpansionControl` currently restates the
   eligibility rule inline, making a third copy beside the validator and the compiler.
   It should call `WikidataFieldGraphTraversalEligibility`, which needs a parts-based
   overload because the editor decides from live controls rather than a saved field.
4. A test per field kind: given a field of kind X, exactly these rows are visible. The
   panel has no test today, and this is the kind it can actually have.

Size: roughly 150–250 lines in one file. Contained — `GridBagLayout` collapses a row
whose components are all invisible, so the running `y++` counters need no rework.

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

One constant per action label removes the "Use" / "Use selected" / "Use selected as
class type (P31)" drift in an hour, with no architecture at all. Worth doing first
whatever else is decided.
