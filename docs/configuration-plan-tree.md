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

Milestones 1 and 2 are implemented. `ConfigurationReferenceInventory` is the typed model-layer
inventory: validation, `ClassDependencies`, class/selection rename discovery and datasource recipe
references consume it. Datasource operations declare references hidden inside their own recipe
grammar, and prepared input requirements are resolved to model sites such as an article
correspondence. The neutral shell and editor conversion have not started.

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
   navigates there and changes nothing.
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

The graph tree and `GraphConfigurationDiagram` are two views of one draft and one selection.
Run, Clear draft and Apply remain explicit commands.

## One draft and one Apply

One selected construct owns one draft, shared by all of its local configuration nodes. Switching
between Definition, Primary source and Refine does not apply partial edits to the project. Apply
commits the complete construct draft and rebuilds its projection from the model.

There are no per-node drafts. A half-edited route step or evidence recipe never reaches execution,
validation or another component. Selecting and expanding nodes never applies, discards, fetches or
runs anything.

Navigation away from a dirty construct still depends on the general Apply / Discard / Stay decision
tracked by #535. Until that decision is implemented, this refactor must not invent a second local
rule. The unified workbench's other components see the last applied project configuration, not
unapplied controls.

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

During the first slice, ModelBuilderApp remains a baseline host of the same configuration component.
Separate entry points remain until #560 decides whether they become thin single-component launchers
or are retired. The cross-process save guard in #536 remains necessary while more than one process
can open the same project.

## Delivery order

Follow #557 as checked milestones:

1. Reconcile this design with the four node kinds and neutral shell contract. **Done.**
2. Add the typed configuration-reference inventory and projections; force validation, dependency
   navigation and rename discovery to read the same facts. **Done.**
3. Classify legacy `FieldSourceType` coverage against the datasource registry. **Done.**
4. Add the neutral tree-and-detail shell with a checked-in rendered artifact.
5. Convert the field editor, covering a datasource field, relation-route field and table-row
   evidence field.
6. Convert the graph editor.
7. Convert the class editors.
8. Complete the separate-process save guard before a second application exposes the complete tree.
9. Let Transform and Datasource views adopt the same projection with ownership-based editability.

The first unified-workbench acceptance slice reaches milestone 5: open one project, select a field,
inspect its complete configuration tree, edit and Apply it through the shared component, and observe
the same resulting model in ModelBuilderApp.

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
- **Checked-in rendering:** a text render beside `docs/panel-layout.txt` records node kind, caption
  and nesting for representative shipped constructs and is enforced by a test.
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
