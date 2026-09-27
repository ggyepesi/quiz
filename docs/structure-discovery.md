# Structure discovery

## Status

First executable slice. TransformApp can start from a measured class relation, form its
connected components, choose a representative, project shared-neighbour links through a
model-declared bridge, inspect both intermediate tables, and render the result with the
existing interactive graph view. The operation reads loaded instances only and changes
nothing.

## Pipeline

```text
loaded members
  → connected components of one relation/converse pair
  → representative with the most distinct shared entities
  → bridge rows linking members to shared entities
  → undirected family links weighted by distinct shared entities
  → ObjectView families + ObjectView links + Cytoscape graph
```

The components are equivalence classes under the reflexive/symmetric/transitive closure of
the selected relation. The input relation itself need not be symmetric or transitive. For
History, `Position.replaces ⇄ Position.replacedBy` therefore produces succession families;
it does not claim that replacement itself is an equivalence relation.

The bridge is ordinary model configuration, not a History-specific rule. It names:

- a row class;
- an entity field from that row to a member of the analyzed class;
- another entity field from that row to the entities shared by families.

History's first configuration is:

```text
relation       Position.replaces ⇄ Position.replacedBy
bridge rows    OfficeHolding
member field   OfficeHolding.position
shared field   OfficeHolding.source
```

The representative count and graph edges are deliberately calculated from the same bridge
rows. The saved `Position.holderCount` is not consulted, because using one population to
choose representatives and another to create edges can contradict itself.

## Results and limits

Every family row retains its original members and holders; every shared-link row retains its
two original representative instances and exact shared holders. The graph is a rendering of
that result, not a second analysis. Only families participating in a shared link are drawn,
so isolated families remain inspectable without overwhelming the network view.

This slice does not save a transformation, create a class, or change the domain. Those are
later pipeline operations if a discovered structure proves worth keeping. Representative
selection currently has one rule—most distinct shared entities, then label and identifier
for a stable tie-break. Making that rule configurable should be forced by a second concrete
use, rather than anticipated here.
