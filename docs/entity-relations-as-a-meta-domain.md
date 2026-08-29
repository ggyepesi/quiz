# Entity Relations as a Meta-Domain

## Status

Design note, not started. Three observations that turn out to be one direction, recorded
with the measurements that support them. Larger than anything in flight; deliberately not
begun before the release.

## The three observations

1. A graph is **another rendering of Viewables**, not a separate kind of thing.
2. Relation discovery should **translate into class configuration**, not grow a private
   model of nodes and facts.
3. The property catalogue is already a **hand-built domain**, and it wants to be an
   ordinary one.

## Edges are already in the model

Nothing needs inventing to express a graph. History today:

```text
Person.spouse            -> Person          a self-reference: an edge
OfficeHolding.source     -> Person
OfficeHolding.predecessor-> Person          edges through a statement class
OfficeHolding.successor  -> Person
OfficeHolding.position   -> Position
```

A typed entity field pointing at its own class **is** an edge, configured through the
ordinary field editor. So a graph view is (instances of a class, a chosen reference
field), and everything a node should show — jurisdiction, instance-of, an image — is
field configuration that already carries sampling, validation, the value-language policy
and media handling.

This supersedes
[Carrying Facts Through Relation Discovery](carrying-facts-through-relation-discovery.md).
That note proposed `NodeFact` to carry an annotation alongside a walked node; field
configuration already carries it, better. The measurement in that note pointed here
without being read that way: *nothing new is needed for the 89.5% case*.

## Discovery translates, it does not accumulate

The workbench already turns exploring into configuring — "Use as class type (P31)", "Add
as Seed QIDs", "Use selected property". Relation discovery is the one exploration with no
such action, so it grew its own node/edge/fact vocabulary instead.

One composite action closes it, assembling calls that already exist:

```text
class          the target type          "Use as class type (P31)"
seedQids       the starting QID         "Add as Seed QIDs"
a field        from the property        useProperty(pid, label)
  type         ENTITY
  entityClass  the same class           the self-reference IS the edge
  propertyPid  the walked property
```

Then the rest composes unasked: `WikidataFieldGraphTraversal.derive` turns any entity
field with a property source and a non-`NONE` policy into a `GraphTraversalStep`, so
setting that field to `CURATED` yields the frontier, the coverage ledger, the
configuration diagram and the expansion workflow — all of it reading the same field.

So the three acts stay separate and each keeps its job:

- **Discovery** — unmodelled, bounded, exploratory. Its output is a decision.
- **Configuration** — where the fields are added, by the editor that knows how.
- **Rendering** — instances of a class, edges from a reference field.

Open question: whether the translator targets the selected class or creates one.
Creating means guessing a name and a population rule from a walk, which is where it can
go quietly wrong. Targeting the selection is unambiguous and matches the other
translators.

## The catalogue is a domain in disguise

`properties.tsv` holds 13,553 rows, produced and consumed by ~356 lines that reimplement
the pipeline in miniature for one type:

| file | lines | what it duplicates |
|---|---|---|
| `WikidataPropertyCacheDownloader` | 84 | generation |
| `WikidataPropertyStore` | 135 | a snapshot format |
| `WikidataProperty` | 23 | a schema |
| `WikidataPropertyScore` | 40 | ranking |
| `PropertyStructuralHints` | 30 | classification |
| `PropertyStructureGroups` | 44 | grouping |

A meta-domain would not add a concept. It would delete a parallel one — and its own edges
are the same self-referencing fields described above: `P279` between classes, `P1647`
subproperty, `P1696` inverse.

Its purpose is configuring ordinary domains: which properties exist, what they relate,
which classes they are about. Today that question is answered by a bespoke catalogue; it
should be answered by a domain, curated with the tools every other domain gets.

### Two cautions

**Scale.** History is 2,390 objects. The catalogue is 13,553 and the class hierarchy far
larger. `properties.tsv` is a flat cached file precisely because it is big and slow to
change; a generated snapshot has a different lifecycle, and generation has never been
exercised at that size.

**Bootstrapping.** Domains would be configured using the meta-domain, and the meta-domain
configured with the same tool. Not vicious — it must only be built once — but the first
build has no catalogue to help it.

## Where the renderer belongs

objectview depends on `slf4j` and `jackson`, and publishes to Maven Central as a
standalone library. JavaFX with WebView is 48 MB of **platform-specific** natives — the
jar resolved here is `javafx-web-21.0.10-mac.jar` — so putting it inside objectview would
change what that library is for every consumer.

The split the current seam already implies:

- **objectview owns the contract** — a graph rendering mode over Viewables, and "edges
  come from this reference field". `GraphViewModel` is that contract in all but name:
  provider-neutral nodes and edges, and a `details` map that is exactly the per-node
  fields a Viewable supplies.
- **The application owns the implementation** — `InteractiveGraphView`, where the
  dependency is.

That is what let GraphStream be replaced by Cytoscape in one file, and it keeps
`datasource.graph` — the candidate open-source boundary — free of any of it.

## Why this is one direction

Each observation is currently propped up by something hand-rolled that it would replace:
a private node/fact model beside field configuration, a bespoke catalogue beside the
generation pipeline, a bespoke diagram beside the rendering family. The direction is the
same in all three — **stop having a second way to do something the model already does** —
which is the rule this codebase keeps rediscovering.
