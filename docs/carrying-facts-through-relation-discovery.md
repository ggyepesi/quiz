# Carrying Facts Through Relation Discovery

## Status

**Superseded** by [Entity Relations as a Meta-Domain](entity-relations-as-a-meta-domain.md).
A walked node needs no way of its own to carry an annotation: a typed entity field
pointing at its own class is already an edge, and jurisdiction is already a field. The
measurement below pointed there — *nothing new is needed for the 89.5% case* — without
being read that way. What remains valid is that measurement, the two bugs in the original
query, and the reason edge qualifiers are a different construct.

Sketch, not implemented. Written from a concrete question — get the subclass structure
of public offices with each office's jurisdiction attached — and from measuring where
that jurisdiction actually lives.

## The question

Walk a subclass tree and see an annotation on every node, e.g.

```text
public office (Q294414)
  └── ... --P279--> office   [jurisdiction: France]
```

A first attempt reaches for one SPARQL query with a `UNION`: take the jurisdiction from
the office, or else from a qualifier on its `P279` statement.

## What the data says

Under `Q294414` (public office):

| | count | share |
|---|---|---|
| offices reached by `P279` | 91,849 | |
| jurisdiction on the OFFICE — `wdt:P1001` | 82,168 | 89.5% |
| jurisdiction on the P279 EDGE — `pq:P1001` | 1,015 | 1.1% |

Those are **two different models**, and a `UNION` merges them into one column that no
longer says which was found:

- On the office: *this office is always about that jurisdiction.* A property of the
  thing.
- On the subclass statement: *this office is a kind of that office **in** that
  jurisdiction.* A property of the relationship.

At 89.5% against 1.1% the first is the model and the second a rare refinement.

## Two bugs worth recording

**The `UNION` is not optional**, so an office with no jurisdiction produces no row at
all. A query asked for "the subclass structure with attached information" returns only
the annotated subset — and the offices it drops are the abstract intermediate ones that
give the tree its shape. Both branches want `OPTIONAL`.

**`?parent wdt:P279* wd:Q294414` is unbounded** over 91,849 nodes: the shape that made a
staged closure necessary in the first place. A workbench view anchors each wave with
`VALUES`, as `DiscoverEntityRelationQuery` already does.

**And the original root was wrong.** `Q2916174` is "residential areas and neighbourhoods",
a Wikimedia list article with zero subclasses — the same class of node #119 was about.

## The model

Nothing new is needed for the 89.5% case:

- `Position.jurisdiction` — an ordinary ENTITY field on `P1001`.
- `Position.subclassOf` — an ordinary typed entity field that declares a
  `GraphTraversalStep` on `P279`. No StatementClass required; that is what field-derived
  traversal is for.
- The qualified edge — **deferred**. If the 1% matters, reify `P279` as a statement class
  (`child`, `parent`, `jurisdiction`), the same construct as `OfficeHolding`. It is the
  expensive half and it answers a rare question.

## The extension

What is missing is not a model. A wave fetches identity only, so the annotation never
arrives. A walk should be able to name the facts it wants about each node it reaches:

```java
/** A fact to carry alongside each reached node — the annotation, not the edge. */
public record NodeFact(String pid, String name) { }

public record Node(String qid, String label, int depth, Map<String, String> facts) { }
```

Each fact becomes one `OPTIONAL` leg with its own label, so a node lacking the fact still
arrives:

```sparql
OPTIONAL { ?target wdt:P1001 ?fact0 }
```

### Three things it has to get right

**Positional variable names.** `?fact0`, never `?P1001`. A PID interpolated into a
variable name is a new bug every time the property changes.

**Truncation must stay honest.** The existing check asks for `rowLimit + 1` and assumes
one row per edge. A multi-valued fact multiplies rows, so `discoveryLimitReached` would
report a cap that never happened. Count distinct edges rather than rows.

**Cost is per fact per wave.** Each `OPTIONAL` widens the join. Two or three are fine;
bound the list rather than discovering the limit in production.

### Where it plugs in

`RelationExploration` carries the facts, so the panel starting a walk says what it wants
brought back. The natural source of that list is the model itself: a `GraphTraversalStep`'s
target class already declares its fields, so "walk `Position --P279--> Position`, carrying
`jurisdiction`" is derivable rather than typed by hand.

Size: roughly 60–80 lines in `DiscoverEntityRelationQuery`, plus the record change and one
control to choose facts. The truncation fix is the fiddly part, not the SPARQL.

## Deliberately not

**Not qualifiers on edges.** This carries facts about NODES. The 1% case — jurisdiction on
the `P279` statement — needs the statement to become an object, which the reify construct
already does. Adding a `pq:` leg here would be a second mechanism for something there is
already one of.

**Not a new traversal.** This is `DiscoverEntityRelationQuery` carrying more per node. The
same walk, used as an admission filter instead of a display, is also what
[#120](https://github.com/ggyepesi/quiz/issues/120) asks for — one mechanism, two
questions.
