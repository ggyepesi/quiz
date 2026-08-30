# Entity Relations as a Meta-Domain

## Status

Design note. The provider-neutral traversal declarations and graph renderer exist, but
field-based traversal is not yet executed by generation and the Viewable projection is not
yet defined. Three observations point in one architectural direction, recorded with the
measurements that support them. The property meta-domain migration is deliberately not
begun before the release; it must not block the smaller class-graph milestone.

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

A typed entity field is an edge configured through the ordinary field editor. A field
pointing back to its owner class gives the simplest, homogeneous graph: (instances of a
class, a chosen self-reference field). Everything a node should show — jurisdiction,
instance-of, an image — is field configuration that already carries sampling, validation,
the value-language policy and media handling.

The examples above are not all homogeneous. `Person.spouse` is; the `OfficeHolding`
fields connect different classes. The first implementation milestone deliberately covers
self-references such as `Position.broaderPosition -> Position`. A later heterogeneous
projection may render the union of source and target classes. The neutral
`GraphTraversalStep` already preserves both class names, so that extension does not
require weakening the traversal model.

This supersedes
[Carrying Facts Through Relation Discovery](carrying-facts-through-relation-discovery.md).
That note proposed `NodeFact` to carry an annotation alongside a walked node; field
configuration already carries it, better. The measurement in that note pointed here
without being read that way: *nothing new is needed for the 89.5% case*.

## Discovery translates, it does not accumulate

The workbench already turns exploring into configuring — "Use as class type (P31)", "Add
as Seed QIDs", "Use selected property". Relation discovery is the one exploration with no
such action, so it grew its own node/edge/fact vocabulary instead.

One composite action closes it, but it produces a proposal rather than invoking editor
buttons directly:

```text
class          the selected class       never inferred or created silently
seedQids       the starting QID         a graph anchor, not the class's P31 type
a field        from the property        useProperty(pid, label)
  type         ENTITY
  entityClass  the same class           the self-reference IS the edge
  propertyPid  the walked property
```

The discovery result does not establish a population type. `Q28470012` may be a useful
Position anchor without being the P31 class that defines all Positions. Population/type
configuration stays independent; if the selected class has no valid population, the
workflow reports that and asks the user to configure it separately.

`WikidataFieldGraphTraversal.derive` already turns a typed modeled entity field with a
Wikidata property source and `CURATED` policy into a `GraphTraversalStep`. Today that step
is shown in the configuration and sample diagrams. It is **not yet consumed by production
generation**: ledger application still reads statement-class `patterns()` only, and no
production caller executes `GraphWave` over `traversalSteps()`. Wiring execution,
coverage, frontier review and durable expansion to the same field is the next milestone,
not an existing consequence.

The action follows the shared workflow:

```text
show proposed configuration
  Target class: Position
  Add anchor: Q28470012
  Add field: broaderPosition -> Position
  Source: Wikidata P279, outgoing
  Graph policy: Curated frontier
apply through one model-level mutation
show the resulting configured graph
```

The mutation belongs below Swing. Reusing field-editor validation and model operations is
desirable; calling UI actions as an integration API is not.

So the three acts stay separate and each keeps its job:

- **Discovery** — unmodelled, bounded, exploratory. Its output is a decision.
- **Configuration** — where the fields are added, by the editor that knows how.
- **Rendering** — instances of a class, edges from a reference field.

Open question: whether the translator targets the selected class or creates one.
Creating means guessing a name and a population rule from a walk, which is where it can
go quietly wrong. The first milestone therefore requires and targets the selected class.
Class creation may later be a separate, explicitly previewed action.

### First class-graph milestone

```text
Entity relation preview
        -> propose configuration
Existing selected class
        + anchor QID
        + typed self-reference field
        + CURATED traversal policy
        -> generate / expand through GraphTraversalStep
Snapshot class instances
        -> GraphProjection(edge field path, node ViewConfig)
Interactive graph rendering
```

This milestone is useful for History on its own. It neither requires nor waits for the
property catalogue migration below.

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

A meta-domain would not add a concept. It would delete a parallel one. It is broader than
the property catalogue, however: `P1647` (subproperty) and `P1696` (inverse) connect
properties, while `P279` connects entity classes. A useful meta-domain therefore needs at
least distinct `Property` and `EntityType` classes (or an explicit common
`WikidataConcept` abstraction); it must not pretend all three relations have Property
instances at both ends.

Its purpose is configuring ordinary domains: which properties exist, what they relate,
which classes they are about. Today that question is answered by a bespoke catalogue; it
should be answered by a domain, curated with the tools every other domain gets.

### Two cautions

**Scale and lifecycle.** History is 2,390 objects and the catalogue is 13,553, but object
count alone is not novel — Oscars already generates more than 30,000 objects. The
unmeasured costs are acquiring the complete property population, enriching its structural
relations, refreshing it on an appropriate cadence, and serving a richer schema instead
of a flat TSV. `properties.tsv` is slow-changing bootstrap data; replacing it requires an
equivalent or better refresh and offline-start story.

**Bootstrapping.** Domains would be configured using the meta-domain, and the meta-domain
configured with the same tool. Not vicious — it must only be built once — but the first
build has no generated catalogue to help it. The existing TSV should remain an explicit
bootstrap input until the generated snapshot proves equivalent; deleting the old path is
the end of the migration, not its first step.

## Where the renderer belongs

objectview depends on `slf4j` and `jackson`, and publishes to Maven Central as a
standalone library. JavaFX with WebView is 48 MB of **platform-specific** natives — the
jar resolved here is `javafx-web-21.0.10-mac.jar` — so putting it inside objectview would
change what that library is for every consumer.

The split the current seam already implies:

- **objectview owns the projection contract** — a graph rendering mode over Viewables:
  node population, edge `FieldPath`, and node `ViewConfig`. It retains field kinds,
  nested values, media, provenance and the existing identity/display contracts.
- **The application owns the implementation** — `InteractiveGraphView`, where the
  dependency is. Its `GraphViewModel` is currently a provider-neutral renderer DTO, not
  yet the Viewable contract: flattening details into `Map<String,String>` loses the
  semantics needed for configured fields to appear automatically.

An application adapter may materialize the ObjectView projection into `GraphViewModel`
for Cytoscape. Adding `Position.jurisdiction` must change the Viewable/ViewConfig, not add
another line that manually copies jurisdiction into the DTO's `details` map.

That is what let GraphStream be replaced by Cytoscape in one file, and it keeps
`datasource.graph` — the candidate open-source boundary — free of any of it.

## Why this is one direction

Each observation is currently propped up by something hand-rolled that it would replace:
a private node/fact model beside field configuration, a bespoke catalogue beside the
generation pipeline, a bespoke diagram beside the rendering family. The direction is the
same in all three — **stop having a second way to do something the model already does** —
which is the rule this codebase keeps rediscovering.

They share that rule, not a release dependency. Implement and validate the class-graph
projection first. Treat the property/entity-type meta-domain as a separate migration with
its own measurements and deletion criterion.
