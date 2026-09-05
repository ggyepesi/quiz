---
description: How authored configuration is shown and edited — what a control may claim, and what a blank means
paths:
  - "app/src/main/java/**/workbench/**/*.java"
  - "app/src/main/java/**/query/swing/**/*.java"
  - "app/src/main/java/**/transform/ui/**/*.java"
---

# Showing configuration

`ui-viewables.md` governs how DOMAIN DATA is shown. This one governs how the model's own
configuration is shown and edited. Every rule below is a defect that shipped, and most of
them agreed with the truth for a while first.

## Blank means unset, and nothing else

**A control shows what is configured, or says which of several things it cannot show.**

A construct with modes is the recurring case: a control that knows one mode renders the
others as empty, and empty reads as *unset*. A class named by the template
`{laureates} — {category}` showed an empty display-name field — which is exactly what
LABEL mode looks like — and the code even carried a guard preserving "a template it
cannot display or edit". Where a control genuinely cannot edit a value, show the value
and close the control; do not show nothing.

## Ask the question the rest of the system asks

**A panel reads the same predicate the validator, the compiler and the server read.**

The subject of a statement has three authored routes; the editor asked about one and
reported a generating domain as unconfigured. The web already knew an owned part is never
a top-level instance; the viewer listed 989 of them beside the classes they belong to. A
second spelling of one question answers differently eventually — derive the yes/no from
the same method that answers the detailed form (`hasStatementSubjectBinding` delegates to
`subjectDestination`), rather than walking the model again.

## A Swing control's selection is never the stored configuration

A multi-select list replaces its selection on a plain click, so clicking a row to READ it
rewrote the class — on Nobel, turning a two-part key into a one-part key from a gesture
that looked like looking. A `JComboBox` selects its first item the moment one is added, so
`Add` acted on something nobody chose. Use `OrderedChoiceList`: the list HOLDS the choice,
selecting a row only says what Remove would take, and a null entry leads the chooser so
"nothing chosen" is a state. `OneChoiceListConstructTest` fails on a fourth hand-written
copy — its tell is a list model and a combo over the SAME type held as fields.

## One result in one place; one question over one walk

Two views of one result disagree about how it looks — a sample rendered in a tab AND a
window differed in size, layout and count. Two counts of one thing disagree about how
many: section headings counted what a reference walk reached while the title counted
roots, so a title said sixteen beside a section saying eight. `ObjectQueryResult.byType()`
is the one traversal; counts and headings both come off it.

## Enablement combines; it never overwrites

`SwingQueryRunner` answers *is something running* and disables every run button; a panel
answers *can this be done with what is selected*. Neither may answer for the other. Re-ask
availability after the runner's blanket toggle, and have availability honour the run state
— fixing one direction alone re-enabled a button in the middle of its own run.

## A control that can never be filled should not exist

A "SPARQL" tab was cleared in three places and filled in none. The query text already
lives on the log node, which renders as a link that opens the Wikidata Query Service on
that exact query. Better than an empty tab is no tab; better than a second copy is the
one that exists.

## A kind, a mode or a role is stored

See directive 12. `classKind` was stored for two of its four values and recomputed for the
other two, so a class could not be a statement class until it had a property — and the
editor that picks the property is the one you reach by being a statement class.
`ClassKindIsStoredNotDerivedTest` holds it, including that the shipped models say which
kind they are rather than leaving it to be inferred.

## Structure and population are different rows

Which field receives an end is STRUCTURE and the model owns it; which entities may occupy
that end is POPULATION and the domain owns it; what a value at that end IS once acquired
is its class and datatype, authored on the receiving field. Three facts, three rows, each
saying which it is — shown as one, "Not configured" and "Anything" read as contradicting
each other when they answer different questions.
