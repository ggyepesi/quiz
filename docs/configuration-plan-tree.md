# Configuration Tree and Detail Editors

## Status

Agreed direction, reconciled with issue #557 on 2026-11-24. Issue #557 is the umbrella and
milestone tracker; this document is the repository design for its configuration projection and
neutral tree-and-detail shell. It supersedes the earlier proposal to add datasource-binding
children to ModelBuilder's main class tree.

The first unified-workbench slice (#560) includes this configuration refactor. ModelBuilderApp
remains runnable as the comparison baseline while both hosts use the same model-layer projection
and editor implementation. Executable operations and their results remain the separate
action/result tree of #558.

Milestones 1 through 9 are implemented. `ConfigurationReferenceInventory` is the typed model-layer
inventory: validation, `ClassDependencies`, class/selection rename discovery and datasource recipe
references consume it. Datasource operations declare references hidden inside their own recipe
grammar, and prepared input requirements are resolved to model sites such as an article
correspondence. `TreeAndDetailPanel` is the neutral shell, and the existing action/result navigator
uses it. The field editor now projects Definition, production, fallback, category, candidate
evidence, references and Refine into that shell while retaining one set of editor controls and one
field-level Apply. Relation-route steps and Wikipedia table-row structure come from their provider
operations rather than being decoded by Swing. The graph editor uses the same configuration-tree
presentation for its start, ordered path nodes, incoming and alternative edges, admission/output
references, evidence, identity and display name; its existing graph draft and single Apply remain
the only authoring path.

Source, Statement, Owned and Aggregate classes use the same local tree and existing
class editors. A class's population node reads the same membership decision generation uses (a
declared non-Wikidata population first, then `MembershipPattern`), so a subclass condition, seeds
or being referenced never appear as an empty slot. Optional sections with nothing set are `+ Add …`
lines. Unlike the graph tree, which projects the unapplied graph, a class tree shows the last
applied class: an edited triple or option reaches its caption on Apply. Definition, population or production, identity and display name have separate
details. Class references use the shared Needs and Used by branches, including links to the
exact graph node that produces a class. Transformation steps are visible on their output class
and remain editable in TransformApp.

Related: [ModelBuilder Constructs](modelbuilder-constructs.md),
[Effective Class Explanation and Uniform Sampling](effective-class-explanation-and-sampling.md),
[Discovery Workbench](discovery-workbench.md),
[Parameterized Generation Pipeline](parameterized-generation-pipeline.md).

## Problem

ModelBuilder's construct tree identifies projects, classes, fields, selections and other stored
constructs. The editor for one selected construct then presents one large conditional form. For
example, `FieldSourcePanel` combines the field definition, production kind, source, property and
qualifier filters, Wikipedia fallback, candidate evidence, ownership, relation routes and refine
settings. Most rows are irrelevant or disabled for any one field. `GraphConstraintsPanel` and the
class editors have the same problem.

A field may also have several independently meaningful stored bindings. `DeityGroup.members` is
the forcing case: primary values come from Wikidata relation routes, while Wikipedia table and
infobox recipes propose candidate evidence. A flat form does not show at a glance what is stored,
what is still available, what the construct needs, or what uses it.

The unified workbench makes this more urgent. Every component sees one live project, so it needs
one answer to “what is configured here?” rather than another component-specific walk of the model.

## Two trees with different meanings

Configuration and execution use one neutral tree-and-detail shell but different typed projections:

- **Configuration tree (#557):** stored configuration, available slots, references and inventory
  summaries. Selecting a node only reveals configuration or an explicit add choice.
- **Action/result tree (#558):** context or datasource, explicit action, and views of the action's
  latest result. Only the action's verb-labelled button starts work.

Configuration nodes never become workflow actions, and workflow nodes never become stored model
constructs or `ViewableGroup`s. The shared shell owns tree selection, expansion, stable identity,
detail placement and navigation only. It does not inspect the model, execute work or interpret
results.

## Placement

ModelBuilder's main construct tree remains the compact project/class/field/selection navigator.
Selecting one construct opens a **local configuration tree on the left and the selected detail
editor on the right** inside that construct's editor pane.

```text
project/class/field navigator | local configuration tree | selected detail editor
```

Configuration sections do not become grandchildren of every field in the main tree; doing so
would swamp project navigation with fields × slots × steps. The local tree is rebuilt from the
current construct draft and uses the same projection in ModelBuilderApp and the unified workbench.

## Configuration node contract

Every projected node has a stable identity, one of four explicit kinds, a caption, and a detail
target. Its kind controls appearance and allowed interaction.

1. **Stored configuration** — a construct or section backed by the selected construct draft.
   Selecting it opens its editor.
2. **Available slot** — a visible `+ Add …` line for an allowed but unconfigured slot. Selecting it
   reveals compatible choices and changes nothing until the user explicitly adds one.
3. **Reference** — a read-only `Needs` or `Used by` link to another configured site. Selecting it
   only names the target; its detail's **Open …** command (or Enter, or a double click) navigates
   there. Arrowing through the tree must never leave the construct being edited.
4. **Inventory summary** — a read-only count or status, such as curation decisions or last-run
   artifacts. Selecting it opens the existing inventory; decisions and run results do not become
   configuration.

Blank configuration is absent, not an empty form. Allowed blank slots remain discoverable through
their `+ Add …` nodes.

### Field example

```text
birthDate
├ Definition                    holds · class · cardinality · display
├ Produced by: datasource value
│  ├ Primary: Wikidata › statement value (P569)
│  │  └ Filter: qualifier P… is one of …
│  ├ Fallback: + Add …
│  └ + Add category relation
├ Candidate evidence
│  └ Wikipedia table rows — Assessors of Maat
│     ├ Section / table / display column
│     └ Field mapping
├ Needs
│  └ EgyptianDeity article correspondence
├ Used by
│  └ canonical identity key …
└ Refine                         required · expectation · numeric filter
```

Relation routes, union paths and candidate-evidence recipes are nested stored configuration, not
encoded into one long caption or hidden behind a combo box.

### Graph example

```text
Position replacement expansion
├ Start                          class or population
├ Path
│  ├ Node 1                      incoming edge · alternatives · admission · use as
│  └ Node 2                      …
├ Output                         class · additive/narrowing · repeat
├ Evidence
├ Identity
└ Display name
```

The graph tree edits one draft. Synchronizing that draft and its selection with
`GraphConfigurationDiagram` is follow-up work. Run, Clear draft and Apply are explicit commands.

## One draft and one Apply

One selected construct owns one draft, shared by all of its local configuration nodes. Switching
between Definition, Primary source and Refine does not apply partial edits to the project. Apply
commits the complete construct draft and rebuilds its projection from the model.

There are no per-node drafts. A half-edited route step or evidence recipe never reaches execution,
validation or another component. Inspecting sections of the current editor preserves its controls.

In the unified workbench a field's draft is resolved explicitly (#535). One method reads the
field controls onto a copy of the model. The editor compares the authored configuration even when
the model has validation errors. Apply checks the proposed change before writing it to the working
model. Invalid input stays in the editor for correction. Inspecting sections, expanding or
scrolling preserves the draft, and restoring an edited value leaves the field clean.

- **Leaving a dirty field** (another construct in the navigator, a reference's Open, or another
  production site of the same field) asks **Apply / Discard / Stay** for the whole field draft,
  shared definition included. Stay is the default and keeps the selection and the draft; Apply runs
  the field's Apply and its validation, and a refused Apply stays with the reason shown; Discard
  reloads the whole field from the working model.
- **Save** is enabled whenever no save runs. With a draft it offers **Apply and save**, **Discard
  draft and save applied changes**, or **Stay**; the shared file confirmation comes first, so
  cancelling it keeps the draft. A failed write keeps the applied changes in memory.
- **Close** is one decision: with a draft **Apply, save and close / Discard changes and close /
  Stay**; with only applied changes **Save and close / Discard changes and close / Stay**. Stay is
  the default; a refused Apply or a failed save keeps the workbench open.

ModelBuilderApp keeps its current behavior until it is retired. Graph and class editors adopt the
same rule when the unified workbench lets them edit; until then leaving a graph node or opening
**+ Add next node** in ModelBuilder replaces that node's unapplied controls. The unified
workbench's other components read the last applied project configuration.

## One model-layer configuration inventory

The tree is a projection of one model-layer inventory. Editors do not independently walk legacy
mappings, source bindings, owned overrides or dependency strings. The same inventory supplies:

- the configuration tree;
- validation;
- dependency navigation;
- rename discovery and centralized rewrites;
- execution planning where the fact is executable.

### Typed configuration sites and references

A configuration site is identified by stable declaration identity plus its field/path and optional
production context. A typed reference identifies both source and target sites and its relation kind.
It covers at least:

- companion subject and match fields;
- qualifier-of references;
- inverse and union sources;
- relation-route terminals;
- population bounds and selections;
- table-row targets and mapped fields;
- resolved datasource-operation inputs.

`ClassDependencies` remains the class-to-class existence/reference graph. The typed inventory
projects its class-level edges there while retaining field-level `Needs` / `Used by` navigation.
Intra-class field references do not become fake class-cycle edges. Rename propagation uses typed
writable sites or one centralized rewrite operation; a display-only string is insufficient.

`DatasourceOperation.inputReferences()` describes source-native inputs. Preparing a configured
operation resolves those schemas into provider-neutral `SourceInputRequirement`s; the model-layer
inventory maps the requirements to concrete model sites, such as a class's article correspondence,
before they appear under `Needs`. The UI never guesses that mapping.

## Datasource binding-site inventory

The earlier datasource-binding design remains a required part of the model inventory. A field's
binding sites include:

- direct `sourceBindings()` in stored order; and
- every `OwnedFieldSource.sourceBindings()` configured at its owned-component production sites,
  in stored production-site and binding order.

Each item carries:

- the field editor that owns the configuration;
- the exact binding target and slot;
- the provider operation and stored recipe;
- for contextual owned fields, the owner-class declaration id and owner-field path.

A singular binding's stable key uses the target class declaration id, field path and slot.
Candidate evidence additionally uses its authored position because the model stores no hidden
recipe identity. Contextual sites include the owning production site's declaration id and path so
equal slots at two sites cannot collide and class renames do not orphan them.

Provider and operation captions, parameter labels, compatible choices and recipe summaries come
from `DatasourceRegistry`, `DatasourceOperation` and `ParameterDescriptor`. The projection never
switches on provider or operation IDs and never decodes a route recipe itself. An incomplete stored
recipe still has a stored-configuration node and its validation problem.

Apply must synchronize legacy-backed mappings with `FieldSourceBindings` immediately. Save is not
the moment at which the tree, validator and `ModelSourceExecutionPlan.compileStored` first agree.
The inventory does not compensate by mutating the model while rendering.

## Registry coverage and retiring the parallel source walk

The local tree eventually replaces the `FieldSourceType` combo and its parallel interpretation,
but only after coverage is explicit:

- every source type persisted by a checked-in model or supported by generation maps to exactly one
  registered provider operation;
- generic sources enter through registry-backed `DATASOURCE`, not new enum branches;
- unused placeholders are removed or explicitly refused;
- `MANUAL` means no datasource binding;
- category evidence and other candidate-evidence sites remain distinct from the primary source.

Saved models retain their current stored source type until a separately approved migration.

The retained legacy choices are classified at the migration boundary, not in the editor:

| Classification | `FieldSourceType` values | Meaning |
|---|---|---|
| Fixed registered operation | `SPARQL`, `DBPEDIA`, `WIKIPEDIA_INFOBOX`, the three Wikipedia article-text projections, and the three Wikidata computed counts | The type maps to exactly one registered `FIELD_VALUE` operation. |
| Binding-selected registry operation | `DATASOURCE` | The configured binding itself names the registered operation; new providers use this path. |
| No datasource | `MANUAL` | No source binding and no generated acquisition. Values come from explicit curation. |

The unused placeholders `WIKIDATA_API`, `BACKLINKS`, `WIKIPEDIA_CATEGORY`, `WIKIPROJECT` and
`COMMONS` were never persisted and had no distinct generation operation, so they were removed.
Wikipedia category evidence remains its existing separate binding site; Commons remains a discovery
source until a concrete field operation is configured through the registry.

## Unified-workbench ownership

The unified workbench owns one live project object (#560). Components edit only the declarations
they own on that object:

- model configuration edits source/class/field/graph declarations;
- transform edits transformations, groups and selections produced from them;
- datasource acquisition reviews and applies fetched values;
- discovery inspects bounded source structure and explicitly promotes reusable findings.

Every component can inspect the complete configuration projection. Ownership determines whether a
stored node's detail is editable, not whether the node exists. There are no private project copies
and no reconciliation step.

For a model-backed project, **Configuration…** in TransformApp and DataSourceApp opens the
ModelBuilder construct navigator and the same local class, field and graph trees over that
session's working model. **Needs** and **Used by** open the same referenced declarations. Inspection
uses cached Wikidata labels. Source fields, class/graph settings and the project build list are
edited in ModelBuilder. DataSourceApp runs configured acquisitions and reviews values through its
action/result navigator. TransformApp edits local groups in the group view and can add or remove
members of a local hand-picked population in Configuration. Imported selections and populations
produced by a transformation are read-only there.

The transformed class's population branch names the input class and shows the ordered filter
steps. TransformApp can add, edit, remove and reorder the steps, then **Apply transformation**
to update the open model. **Save model/domain** persists the applied recipe and **Build project**
in ModelBuilder executes it to update the produced instances and publication. Inspecting another node keeps the
draft and starts no build or acquisition. ModelBuilder and DataSourceApp show the same steps
read-only.

During the first slice, ModelBuilderApp remains a baseline host of the same configuration component.
Separate entry points remain until #560 decides whether they become thin single-component launchers
or are retired. The cross-process save guard (#244, fixed) remains necessary while more than one process
can open the same project.

## Delivery order

Follow #557 as checked milestones:

1. Reconcile this design with the four node kinds and neutral shell contract. **Done.**
2. Add the typed configuration-reference inventory and projections; force validation, dependency
   navigation and rename discovery to read the same facts. **Done.**
3. Classify legacy `FieldSourceType` coverage against the datasource registry. **Done.**
4. Add the neutral tree-and-detail shell with a checked-in rendered artifact. **Done.**
5. Convert the field editor, covering a datasource field, relation-route field and table-row
   evidence field. **Done.**
6. Convert the graph editor. **Done.**
7. Convert the class editors. **Done.**
8. Complete the separate-process save guard before a second application exposes the complete tree.
   **Done** for model-backed projects. See [concurrent saves](datasource-app.md#concurrent-saves).
9. Let Transform and Datasource views adopt the same projection with ownership-based editability.
   **Done.**

The first unified-workbench slice is available in `domain.workbench.unified.app.UnifiedWorkbenchApp`.
Open a saved model or domain with an instance snapshot, select a field, and use the same field
editor as ModelBuilder. **Apply field source** updates the working model and the **Field acquisition
plan** in the same window. The preview reads the plan compiler used by Generate and Enrich.
Selecting a field or reading its plan sends no datasource requests.
Leaving, saving or closing over unapplied edits asks first, as described under
[One draft and one Apply](#one-draft-and-one-apply).

**Save model** or **Save domain** writes the project's `.model.json` through the shared concurrent-save
guard. Reopening the project shows the saved field and plan. The snapshot still contains the
previous instances. Change the field, Apply, and run acquisition to refresh their values.

## Forcing tests and checked artifacts

- **One projection:** every host receives the same node identities, kinds, captions and order for
  the same construct draft.
- **Inspection only:** selecting or expanding every node of every shipped model changes no model
  value and makes no request.
- **One inventory:** projected datasource nodes exactly equal the binding-site inventory, including
  contextual owned fields.
- **Both source roles visible:** `DeityGroup.members` shows Wikidata primary routes and Wikipedia
  candidate evidence separately.
- **Allowed slots visible:** compatible unconfigured slots appear as `+ Add …` and do nothing on
  selection.
- **References agree:** validator, dependency navigation and rename discovery use the same typed
  references.
- **Apply before Save:** Apply makes the tree and execution plan report the same configuration
  without requiring Save.
- **Stable rename:** renaming a target or context class retains source nodes and selection.
- **Checked-in rendering:** `docs/tree-and-detail-layout.txt` records node kind, caption and nesting
  for the neutral configuration-shaped fixture and proves that the existing action/result navigator
  consumes the same shell. Milestone 5 extends the artifact with projected shipped constructs.
- **Host parity:** editing the forcing fields through ModelBuilderApp and the unified workbench
  produces the same serialized model.

## Non-goals

- Adding every configuration section beneath the main project/class tree.
- Treating configuration nodes as executable actions or action-result nodes as configuration.
- Starting discovery, preview, acquisition, generation or transformation by selecting a node.
- Persisting tree expansion, selection or status in `model.json`.
- Storing curation decisions or run results as configuration nodes.
- Reimplementing datasource compatibility or parameter interpretation in Swing.
- Building a graph workflow language or a second execution pipeline.
