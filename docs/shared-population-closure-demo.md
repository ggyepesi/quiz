# Shared-population closure demo

Run the Swing UI:

```text
wikidata.explore.demo.closure.SharedPopulationClosureFrame
```

It provides editable configuration, Run/Cancel, live SPARQL progress, and sortable tables
for values, population members, witnessed connections, and journal events. Physical
requests, retries and failures are shown in Progress as well as echoed to stdout.

The console-only entry point remains available as:

```text
wikidata.explore.demo.closure.SharedPopulationClosureDemo
```

The default configuration follows this relation:

```text
Apostolic King of Hungary (Q6412254)
    <- position held (P39) - person - position held (P39) -> other position
```

Discovered target values are restricted by default to either `position` (`Q4164871`)
or `monarch` (`Q116`) through a mixed `instance of`/`subclass of` path. This admits
position-like concepts such as monarch and emperor while rejecting organizations such
as the Sejm. The UI accepts a comma-separated alternative in **Allowed target roots**;
an empty value disables semantic filtering. Each completed wave reports how many target
entities the filter rejected.

For every breadth-first wave, frontier QIDs are partitioned into SPARQL `VALUES`
batches. Each map unit uses bounded stages: population-member IDs, next-position
expansion in member batches of 40, type filtering in batches of 40, and label lookup in
batches of 100. The inner partitions also run through `BatchExecutor`, so they retry and
adaptively split independently; completed physical requests are retained for the life of
the demo run so a later-stage retry does not repeat them. This deliberately
avoids a single join that can create a large intermediate result and a truncated WDQS
JSON response. The demo also uses HTTP/1.1 to avoid intermittent WDQS HTTP/2
`RST_STREAM` failures.

`BatchExecutor` runs the outer map units and adaptively splits a frontier batch when the
endpoint reports that it is too heavy. The reducer keeps the complete population,
deduplicates position-to-position edges, excludes already visited positions from the
next frontier, and assigns minimum discovery depth.

The checkpoint and committed-result stores are deliberately in memory. The journal says
which partitions completed; the result store retains what each completed partition
produced, so resuming an interrupted wave does not lose its earlier partitions. The
console prints its ordered
`START`, `SPLIT`, `COMPLETE`, and `FINISH` events after the traversal. It exercises the
same checkpoint protocol as the file-backed store but does not survive JVM termination.

Optional program arguments, in order:

```text
startValueQid populationPropertyPid nextWavePropertyPid maxDepth batchSize maxValues targetRootQids
```

For example, the defaults are equivalent to:

```text
Q6412254 P39 P39 2 8 100 Q4164871,Q116
```

`maxValues` bounds the discovered-value set. When the bound is reached, the current
wave remains visible, but excess targets are not admitted to the next frontier and the
run stops with an explicit message.

`maxMembershipsPerUnit` and `maxConnectionsPerUnit` separately bound acquisition before
the reducer. Their defaults are 2,000 and 5,000. Reaching either stops after the current
wave and marks the result incomplete; it is not reported as a complete smaller closure.

Target hierarchy membership is the union of `subclass of*` and
`instance of / subclass of*`. It deliberately does not use `(P31|P279)*`, which would
also admit arbitrary alternating instance/subclass paths.
