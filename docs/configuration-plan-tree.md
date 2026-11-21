# Configuration Tree and Dependency Graph UI

## Status

Proposed on 2026-11-21. This document defines the new configuration user interface over the
existing ModelBuilder constructs: a selectable configuration tree, contextual step editor and
discovery support, plus an optional dependency-graph view. It does not introduce a second
persisted configuration format. The first forcing case is `DeityGroup.members`, whose
population, Wikidata routes, Wikipedia table evidence, discovery, review and output are
currently configured in separate parts of the UI.

This design complements [ModelBuilder Constructs](modelbuilder-constructs.md),
[Effective Class Explanation and Uniform Sampling](effective-class-explanation-and-sampling.md),
[Discovery Workbench](discovery-workbench.md), and
[Parameterized Generation Pipeline](parameterized-generation-pipeline.md).

## Problem

The saved model is increasingly capable, but the editor exposes its capabilities as flat or
loosely related controls. A field may now have:

- a declared type and cardinality;
- a primary datasource operation;
- a fallback source;
- candidate evidence which requires review;
- routes, filters, bounds or source-specific parameters;
- contextual discovery actions;
- validation and preview;
- downstream transformations or publication.

Each control can be correct while the complete workflow remains hard to see. A user should not
have to remember that a Wikipedia structure is discovered in one panel, authored as candidate
evidence in another, executed from TransformApp, and applied only after review.

The problem is not a lack of another workflow language. The existing model already owns the
decisions. The missing construct is one readable projection of those decisions and of the
valid next actions.

## Decision

The primary configuration UI is a **typed configuration-plan tree** derived from the working
model. An optional read-only dependency graph presents the same plan when the question is
"what depends on this?". The tree is the editor because exact fields, operations, parameters,
bounds and conditions are hierarchical and are difficult to edit reliably in a graph.

```text
working model constructs
        |
        v
typed ConfigurationPlan
        |-------------------|---------------------|
        v                   v                     v
tree editor        dependency overview      validation/build plan
```

`ConfigurationPlan` is not serialized. Every editable node points to the declaration and
source binding which owns the fact. Applying an edit changes that existing owner through its
existing editor/service. Recompiling the plan immediately reconstructs the tree.

## Window layout

The configuration window has one stable layout instead of opening unrelated editors for each
kind of construct:

```text
┌ Project / construct breadcrumb ─────────────────────────────────────────────┐
│ Egyptian Gods › DeityGroup › members                         Valid, unsaved │
├ Configuration ───────────────┬ Selected step ───────────────────────────────┤
│                              │ Field: members → EgyptianDeity[]             │
│ ▾ DeityGroup                 │                                              │
│   ▸ Population               │ Candidate evidence                          │
│   ▸ Identity                 │ Wikipedia article table column               │
│   ▾ members                  │                                              │
│     ✓ Type                   │ Section: [Description > List of names…     ] │
│     ✓ Primary values         │ Table:   [(any table)                      ] │
│     ▾ Candidate evidence     │ Column:  [Name of the deity                ] │
│       ✓ Wikipedia table      │                                              │
│       ◇ Discover structure   │ [Inspect examples] [Discover…] [Preview…]   │
│       ○ Review results       │                                              │
│     ▸ Output                 │ This proposes loaded EgyptianDeity instances │
│                              │ for review; it creates no identities.        │
├ Latest preview / discovery / review ───────────────────────────────────────┤
│ 42 rows · 38 loaded targets · 4 unresolved: …                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

The left side answers **where am I in the configuration?** The right side answers **what does
this step do, what is configured, and what can I do next?** The bottom area contains the latest
bounded result for the selected node; it is not a second configuration editor.

The divider is resizable. The tree remains usable at its minimum width, while the selected-step
panel receives the remaining space. Long values wrap or use their normal specialized editor;
they do not widen the whole window.

## Selection and editing behavior

Single selection in the tree changes only the selected-step panel. It never applies a change,
starts discovery, fetches a source, or discards pending edits. If the current editor is dirty,
navigation retains its draft and marks that node as modified; an explicit Apply or Revert acts
on that node. Saving the project flushes valid applied edits through the existing project save
path and names the exact file.

Each selected-step panel has the same shape:

1. the concept and its effective value;
2. the owner of that value;
3. status and an exact reason;
4. the typed editor, when editable;
5. explicit inspect, discover, preview or execute actions;
6. the consequence of Apply, including what will be replaced.

An inherited or imported value remains visible at the place where it affects the selected
construct. Its owner is named, and editing navigates to that owner instead of creating a local
shadow silently.

## Offering possible substeps

The tree contains configured steps and valid unconfigured possibilities. Possible substeps use
a visually distinct state and explain why they are offered. For example, an entity-list field
with a declared target class may offer:

```text
Add source or evidence…
├─ Primary values
│  ├─ Wikidata property
│  ├─ Wikidata relation routes
│  └─ Configured datasource operation…
├─ Fallback values
│  └─ Wikipedia infobox value
└─ Candidate evidence
   ├─ Introduction links/mentions
   ├─ Rendered infobox-row links
   └─ Article table-column links
```

Choices come from datasource operation metadata and field compatibility, not a menu maintained
independently from execution. Inapplicable choices are normally absent. If showing one is useful
for learning, it is disabled with the concrete missing requirement, such as “requires an Entity
list with a declared target class.”

Choosing a possibility creates an explicit draft node. It does not yet replace the existing
binding. The selected-step panel states the source slot and previous value before Apply.

## Discovery within the selected step

Discovery is contextual to the step being configured. For a Wikipedia table-column source:

```text
Discover structure…
  1. Choose or reuse a bounded instance sample.
  2. Fetch only the explicitly selected articles.
  3. Show their retained section → table → column hierarchy in ObjectView.
  4. Select one structural pattern and inspect coverage/examples.
  5. Use as configuration — copies section/table/column into the draft.
  6. Preview — shows which loaded typed instances it would propose.
```

The discovery result is shown under its configuration node and may be reopened while the window
stays open. It does not appear as another unrelated modal workflow. Existing discovery
workbenches remain the implementation owner and can still open in their full form; the selected
node supplies their scope and receives only an explicitly chosen recipe.

## Preview, execution and review

These are separate actions and separate node states:

- **Preview** runs the configured operation with declared preview bounds and changes nothing.
- **Execute** runs the ordinary generation/enrichment/build operation.
- **Review** accepts or rejects candidate evidence.
- **Apply configuration** changes the working model but performs none of the above.
- **Save project** writes the working model and reports the exact file.

The latest result remains attached to its node, including elapsed time, counts, partial/failure
status, source requests and unresolved examples. Closing or switching nodes does not make a
running operation disappear; its status remains visible in the tree and the shared executor
window can be reopened.

## Tree and graph switch

The main navigation offers **Tree** and **Dependencies** tabs over the same selected node and
the same compiled plan:

```text
[Tree] [Dependencies]
```

The dependency graph contains only semantic dependencies—produces, consumes, bounds, imports,
transforms and publishes. It does not draw every field as an edge by default. Selecting a graph
node selects the corresponding tree node and opens the same selected-step panel. Editing occurs
only through that panel, never directly by dragging graph nodes or edges.

## Example

The Egyptian-deity group configuration should read as one workflow:

```text
DeityGroup
├─ Population
│  └─ Wikipedia category: Groups of Egyptian deities
├─ Identity
│  └─ Wikipedia article → Wikidata entity
├─ Field: members → EgyptianDeity[]
│  ├─ Primary values
│  │  └─ Wikidata relation routes
│  │     ├─ group P527 member
│  │     ├─ member P361 group
│  │     └─ member P463 group
│  ├─ Candidate evidence
│  │  └─ Wikipedia article table column
│  │     ├─ Section: Description > List of names, provenances and tasks…
│  │     ├─ Table: any table in that section
│  │     └─ Column: Name of the deity
│  ├─ Discovery
│  │  ├─ Inspect example articles
│  │  ├─ Discover sections, tables and columns
│  │  └─ Preview links resolving to loaded EgyptianDeity instances
│  └─ Review
│     └─ Accept or reject proposed members
└─ Output
   └─ Configured ViewableGroup instances
```

The tree makes the distinction between primary values and candidate evidence visible. The
Wikipedia table cannot silently replace the Wikidata relation routes and cannot create a deity
which is not already a loaded instance of the declared target class.

## Plan nodes

Every node has:

- a stable key derived from declaration IDs, field paths, binding slots and operation IDs;
- a kind;
- an owner reference to the existing construct;
- a concise explanation derived from that construct;
- a status and exact reason;
- zero or more allowed actions;
- child nodes in semantic execution order, not Swing layout order.

Initial node kinds are:

```text
PROJECT
CLASS
POPULATION
IDENTITY
FIELD
PRIMARY_SOURCE
FALLBACK_SOURCE
CANDIDATE_EVIDENCE
CONDITION_OR_BOUND
DISCOVERY
PREVIEW
REVIEW
TRANSFORMATION
GRAPH_CONSTRAINT
PUBLICATION_OR_OUTPUT
```

This list describes presentation roles. It does not duplicate model enums. A primary source
node, for example, points to a `SourceBinding` in `PRIMARY_FIELD_VALUE`; a transformed class
points to its existing `TransformationClassSource`.

## Status

Status answers what is true now, not merely which controls contain text:

- **Configured** — the owning construct is valid.
- **Required choice missing** — execution cannot proceed and the missing choice is named.
- **Optional** — no value is configured and none is required.
- **Discovery available** — an explicit bounded discovery action can offer choices.
- **Preview available** — the current configuration can be inspected without mutation.
- **Needs review** — evidence exists but cannot become data without a decision.
- **Invalid** — validation failed, with the owning construct and reason.
- **Produces no values** — a successful bounded preview/run answered empty.
- **Stale** — a materialized artifact no longer matches its producing configuration.

Statuses come from existing validators, compilers, previews, build manifests and review stores.
The plan must not reimplement their rules. If a rule cannot currently be obtained from its
owner, that owner is improved before the tree displays the status.

## Actions

Selecting a node shows only actions which are valid for that node and current model state.
Actions fall into four explicit categories:

1. **Inspect** — show explanation, retained source evidence or dependencies. Never mutates.
2. **Discover** — run a bounded source query and present candidates. Never applies a candidate.
3. **Configure** — open the existing typed editor with the exact owning construct selected.
4. **Execute** — preview, generate, enrich, review or build through the shared execution path.

No selection starts network work. No discovery result changes the model until the user invokes
an explicit action such as **Use as source**, and that action states exactly which construct
and parameters it will replace.

## Discovery support

Datasource operations describe the discoveries which can help configure them. The plan should
not contain a growing switch over provider IDs. A datasource operation may advertise contextual
helpers such as:

```text
article-table-column-links
  requires: an example with an English Wikipedia article
  offers:
    inspect retained article structure
    choose exact section path
    choose optional table caption/name
    choose exact column header
    preview typed loaded targets
```

Discovery results retain their source structure and provenance. A choice copies only the
provider-neutral parameters into the existing `SourceRecipe`. The result object itself never
becomes hidden configuration.

## Tree and graph

The tree and graph answer different questions:

- The tree answers **what is configured here and what may I do next?**
- The graph answers **what consumes, produces or depends on this construct?**

The graph is read-only initially. Its nodes use the same plan-node keys and statuses, and
selecting a graph node selects the corresponding tree node. It must not introduce a separate
graph-specific derivation of sources or dependencies.

## Relation to existing views

The plan should factor together, not replace locally:

- ModelBuilder's construct tree and field/source editors;
- the effective-class explanation;
- the Explorer and Wikipedia Discovery workbenches;
- TransformApp's validation, evidence and review actions;
- the generation/build execution plan and log.

The existing editors remain the first editing surfaces. The configuration tree initially
navigates to them with the correct construct selected. Editors can be embedded node-by-node
later only when the shared edit service is already independent of their Swing panel.

## Proposed API boundary

```text
ConfigurationPlanCompiler
  compile(GeneratedProjectModel, ProjectArtifacts, ReviewState)
      -> ConfigurationPlan

ConfigurationPlan
  roots
  node(stableKey)
  dependencies

ConfigurationPlanNode
  key
  kind
  owner
  explanation
  status
  actions
  children

ConfigurationPlanAction
  availability and reason
  effect description
  existing command/editor/executor reference
```

The compiler delegates to existing model compilation and validation. It may assemble results;
it may not infer an alternative source binding, class population or execution policy.

## Incremental delivery

1. **Read-only field-source slice.** Project `DeityGroup.members` and comparable fields into a
   tree showing type, primary source, candidate evidence and exact parameters. Add navigation
   to the existing editors and discovery workbench.
2. **Status and action ownership.** Replace UI-local enablement rules with reusable action
   availability plus a human reason, then consume it from both old controls and tree nodes.
3. **Class population and identity.** Add class population, article correspondence, bounds and
   preview nodes.
4. **Execution and review.** Link tree nodes to the same preview/generate/enrich/review commands
   used today and show their latest outcomes.
5. **Project pipeline.** Add transformations, graph constraints, publications and artifacts.
6. **Dependency graph.** Render the already-compiled node dependencies as an optional overview.

The first milestone deliberately does not redesign every editor. It proves that the plan is a
faithful projection and that one user-visible workflow no longer has to be reconstructed from
several panels.

## Forcing tests

- A configuration-plan node and the existing field editor resolve the same source binding and
  parameters for every binding slot.
- Selecting or expanding any node performs no model mutation and no network request.
- Every enabled action has an owner and an explanation; every disabled action has a reason.
- Applying an edit through the existing editor and recompiling changes the corresponding node,
  with no second state to synchronize.
- Renaming a class preserves nodes connected by declaration ID.
- The `DeityGroup.members` tree displays both Wikidata primary routes and Wikipedia table
  candidate evidence, never collapsing one into the other.
- Preview and execution consume the same compiled source recipe; preview differs only by its
  declared bounds.

## Non-goals

- A general-purpose workflow language.
- A graph editor for exact source parameters.
- Persisting Swing layout, expanded nodes or plan status in `model.json`.
- Automatically accepting discovery results.
- Hiding the concrete filename, datasource operation, selection or artifact involved in an
  action.
