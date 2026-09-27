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

Every family row retains its original members and shared entities; every shared-link row
retains its two original representative instances and the exact entities they share. The
graph is a rendering of that result, not a second analysis.

Two rules decide what it draws, and the rows are complete regardless of both. A family no
drawn link reaches is left out. So is a link carrying fewer shared entities than the reader
asks for: over History the pipeline finds 1,610 links of which 755 rest on a single shared
person — one office-holder who happened to hold two unrelated offices — which buries the
hundred carrying ten or more. The view opens at two, the weakest claim worth reading, and
the control says how many of the result's links and families are drawn at the current floor.

A shared neighbour is a **hypothesis, not a conclusion**, and the three kinds it finds are
not distinguished. Reichstag of the Weimar Republic and Reichstag of Nazi Germany share 295
holders because they are one institution renamed; Civil Governor of Madrid and procurador
en Cortes share 60 because they are rungs of one career; Politburo and Supreme Soviet share
48 because one elite filled both. Only the first is evidence that two families are one
office, and nothing in the result says which case a link is.

A heavy link between two families is therefore also a finding about the RELATION that
formed them: 295 people held both Reichstag offices while `replaces`/`replacedBy` never
connected them, which is a candidate missing statement rather than a fact about people.

This slice does not save a transformation, create a class, or change the domain. Those are
later pipeline operations if a discovered structure proves worth keeping — and when one is,
a family is a `PopulationSelection` or a produced group, the constructs directives 18 and 23
already define for a reusable set of instances. It must not become a third. Representative
selection currently has one rule—most distinct shared entities, then label and identifier
for a stable tie-break. Making that rule configurable should be forced by a second concrete
use, rather than anticipated here.
