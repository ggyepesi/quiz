# Datasource Bindings in the Construct Tree

## Status

Proposed on 2026-11-21, revised after review (#533). The explicit configured-child list in the
field editor and the DataSourceApp action/result tree are implemented. Adding those same binding
children to ModelBuilder's construct tree remains proposed.
This document specifies one slice: showing every stored datasource binding of a field in
ModelBuilder's existing construct tree. It does not claim to show every way a field can be
produced: inverse, union, statement-end, owned-component and other derived productions remain
the field editor's responsibility in this slice. Later directions are listed, not designed,
and one navigation decision is left open on purpose (see **Open decisions**).

Related: [ModelBuilder Constructs](modelbuilder-constructs.md),
[Effective Class Explanation and Uniform Sampling](effective-class-explanation-and-sampling.md),
[Discovery Workbench](discovery-workbench.md),
[Parameterized Generation Pipeline](parameterized-generation-pipeline.md).

## Problem

A field can now have several datasource bindings at once. Primary, fallback and category sources
occupy singular slots; candidate evidence is an ordered additive list in one slot. The forcing case is
`DeityGroup.members` in Egyptian gods. Its primary values come from three Wikidata relation
routes, while a Wikipedia article table recipe and a rendered-infobox Members recipe propose
additional members as candidate evidence for review.

Nothing shows both together. The construct tree stops at the field; the field editor shows the
primary source in one section and the candidate evidence in another, behind a chooser. A user
cannot see at a glance that the field has two sources, which one is primary, or that the second
one only proposes values for review.

## Decision

ModelBuilder's construct tree (`SingleRootClassModelPanel`) already shows
project › class › field › child field, plus vocabularies/populations and uses. **The field
node gains one child per stored datasource binding configured at that field.** There is no
second tree and no new window.

```text
▾ DeityGroup
  ▾ members → EgyptianDeity[]
      Primary values · Wikidata relation routes (3)
      Candidate evidence · Wikipedia article table rows · List of names…
      Candidate evidence · Wikipedia infobox row links · Rendered.Members
  ▸ …other fields
```

- A source node shows its slot, its datasource operation and a short summary of its
  parameters. The full parameters appear in the field editor; the tree does not repeat them.
- Provider and operation names, parameter labels and parameter summaries come from the
  `DatasourceRegistry` operation and its `ParameterDescriptor`s. The tree never switches on
  provider or operation IDs. Where descriptors cannot summarize a recipe, for example the
  route count of `relation-routes`, whose routes are one JSON parameter, `DatasourceOperation`
  gains a recipe-summary method that the operation implements. The tree does not decode a
  recipe itself.
- Source nodes come before child-field nodes and look different from them. A source is not a
  field and must never be mistaken for one.
- A stored binding has a node even when validation says that its recipe is incomplete. A slot
  with no binding has no node. The tree shows what is stored, not what could be configured and
  not a second opinion about whether the recipe is valid.
- Selecting a source node opens the existing field editor for its field, with that exact
  binding site and slot scrolled into view. Selecting is inspection only: it never applies an
  edit, starts discovery or fetches anything (directive 10).
- Editing happens in the field editor exactly as today. After Apply, the tree rebuilds from
  the model, so there is no second state to keep in step.
- A singular source node's key is its target class's declaration id, field path and binding slot.
  Candidate-evidence children additionally use their position in the field's authored recipe list;
  the model stores no hidden recipe identity.
  A contextual owned-field source additionally includes the production site's owner-class
  declaration id and owner-field path. Keys therefore survive class renames without making
  two production sites collide.

### Source-node selection and editor ownership

A source node is navigation metadata, not a new model construct and not an editor owner.
`ModelSourceWorkbenchPanel.selected` remains the `GeneratedFieldModel` whose editor is open.
Selecting a source node therefore resolves to two separate inputs:

1. the field editor which owns the binding; and
2. the exact binding target and slot which that editor reveals.

For a direct binding, the editor owner is the target field. For a contextual owned-field
binding, the editor owner is the ownership/production-site field, because that is where its
override is authored; the revealed target still names the owned class and owned field.
The source-node presentation object is never passed to `changeSelection` as though it were a
class or field. If its owner field is already open, selection only calls the field editor's
reveal operation.

Navigation to another editor follows the construct tree's existing rule, unchanged by this
slice: `changeSelection` applies the open editor before showing the next one. Whether that
silent apply should become an explicit **Apply / Discard / Stay** choice is open decision 1
(#535). It is a rule for every editor, not source-node behavior. This slice works under either
rule: a source node under the field already open only reveals a binding and replaces no
editor. There are no per-node drafts.

### One source of the slot list

Source nodes are read through one read-only binding-site inventory owned by
`FieldSourceBindings`, the same construct which supplies bindings to validation and execution.
It includes:

- a field's direct `sourceBindings()` in stored order; and
- every `OwnedFieldSource.sourceBindings()` configured at that field's owned-component
  production sites, in stored production-site and binding order.

The inventory returns the binding together with the field editor that owns its configuration.
The tree must not walk owned overrides or interpret legacy mappings separately.

Precondition: in memory, a field's bindings can lag its legacy mapping until Save runs
`FieldSourceBindings.synchronizeForSave`. Apply must first keep the binding inventory current,
so the tree, validation and `ModelSourceExecutionPlan.compileStored` immediately see the same
configuration without Save. That fix belongs in `FieldSourceBindings`; the tree does not
compensate or synchronize as a side effect of rendering. It is the first step, committed on
its own before any tree work, because every editor that still writes a legacy mapping must
update its binding on Apply.

### What stays as it is

- Save still explicitly applies the active editor before writing, and navigation keeps the
  existing apply-on-navigation rule until open decision 1 is settled.
- Discovery, preview, generation, enrichment and review keep their current entry points.
  The tree adds none of them in this slice.
- Nothing about the tree is saved in `model.json`.

## Forcing tests and artifact

- **Checked-in render.** Add the source nodes of every shipped model's fields to a
  checked-in text render, beside `docs/panel-layout.txt`, enforced the same way as
  `PanelLayoutIsCheckedInTest`. A reordered, missing or collapsed slot then shows up in
  review, not only as a passing suite (directive 16).
- **One inventory.** For every field of every shipped model, the source nodes equal the
  `FieldSourceBindings` binding-site inventory in its declared order: same targets, ownership
  contexts, slots and operations.
- **Apply before Save.** Change each legacy-backed primary/fallback/category editor in turn and
  press Apply without Save. The tree and `ModelSourceExecutionPlan.compileStored` immediately
  report the same replacement binding.
- **Both sources visible.** `DeityGroup.members` shows its Wikidata routes as Primary values
  and its table column as Candidate evidence, as two nodes.
- **Contextual source visible.** A field producing an owned component shows every configured
  owned-field override with its target field and production-site context; equal slots at two
  sites have different keys.
- **Inspection only.** Selecting or expanding any node of any shipped model changes nothing
  in the model and makes no network request. Selecting a source node under the field already
  open never applies or rebuilds that editor; it only reveals the binding.
- **Editor ownership.** Selecting a direct or contextual source node leaves the workbench's
  selected object equal to the owning `GeneratedFieldModel` and reveals the exact binding; a
  source-node presentation object is never recorded as the editor owner.
- **Rename.** Renaming a target or context class keeps its source nodes and their selection.

## Open decisions

These are not settled by this document and must be agreed before work that depends on them.

1. **Pending edits on navigation (#535).** Today, selecting another node applies the open
   editor silently. The alternative is an explicit **Apply changes / Discard changes / Stay
   here** choice, which directive 10 argues for. It needs an exact unapplied-changes check for
   every editor; only `ClassSourcePanel` has one today. This slice keeps the existing rule.

## Execution and review home

Datasource bindings and candidate evidence are configured in ModelBuilder, inspected without
mutation in DiscoveryApp, and run/reviewed in DataSourceApp over a loaded project. TransformApp
owns local grouping, transformations and manual edits only. DataSourceApp and TransformApp share
the class/group/field scope UI and invoke the same headless processes; neither drives the other.
The construct tree may therefore navigate to configuration or read-only discovery, but Execute
and Review belong to DataSourceApp and must remain explicit actions there.

## Possible later directions

Listed so they are not lost; none is designed or agreed, and each needs its own forcing case.

- Class-level nodes for population source and identity (the article correspondence).
- An **Add source or evidence…** action on a field, offering only operations compatible with
  the field's type, taken from datasource operation metadata.
- Contextual discovery launched from a source node, scoped by it, returning only an explicitly
  chosen recipe.
- Showing the latest preview or run result for a source node, from the existing result stores.
- A read-only dependency view (produces, consumes, bounds, imports, transforms, publishes) over
  the same nodes.

## Non-goals

- A second construct tree or a separate configuration window.
- A workflow language or a graph editor for source parameters.
- Unconfigured possibilities shown as tree nodes.
- Persisting expansion, selection or status in `model.json`.
- Automatically accepting discovery or evidence results.
- Presenting inverse, union, statement-end, owned-component or other non-datasource field
  production as datasource-binding nodes.
