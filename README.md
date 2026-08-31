# quiz

A workbench for building typed knowledge domains from Wikidata, and the quiz web client
that consumes them.

You build a domain model against Wikidata in a Swing workbench, generate a snapshot of
real instances from it, curate that snapshot, and serve it. Each of the domains below is
configuration — a saved model plus the snapshot it produced — rather than an extractor
written for it. (Older hand-built domains still live in the tree, and are being migrated
onto the same snapshot format.)

## What it produces

Seven domains built through the same workbench, each with a saved model and the object
counts it generates:

| domain | objects | shape |
|---|---|---|
| Oscar nominations | 33,796 | nominations reified from `P1411` statements, with ceremonies, works and nominee types |
| Movies | 20,000 | a large flat population |
| Nobel prizes | 2,378 | awards reified from `P166`, grouped into prizes by category and year |
| Constellations | 635 | stars and the constellations they belong to |
| Greek mythology | 557 | characters and episodes |
| History | 449 | people, positions, and office holdings as reified statements |
| Periodic table | 175 | elements |

## The idea

Wikidata is a graph of statements, not of records. Most of what a domain needs — *"who
won which Nobel prize, in which year, for what"* — is a statement plus its qualifiers,
and has no item of its own. So the model is built from four kinds of class:

- **Source** — an entity with its own identity: a person, a star, an element.
- **Statement** — a reified statement and its qualifiers, identified by a natural key you
  declare. One Oscar nomination, one Nobel award.
- **Owned** — a component produced per owning instance, identified by its owner and the
  site that made it. A structured name belongs to its person.
- **Aggregate** — the group that records fall into, built offline from records already
  materialized. Nobel's 634 prizes exist only as the categories and years its 716 awards
  share.

Two more constructs carry the parts a graph does not hand you directly: a **vocabulary**
bounds a value domain to a named set of entities, and a **curated frontier** grows a
population one explicit decision at a time — the machine proposes what a relation
reaches, and the modeller decides whether it belongs. An automatic traversal grows into
regions the domain does not want; a fixed depth cannot express *"not that
neighbourhood"*.

Identity, provenance and coverage are first-class throughout. Every value knows which
source produced it, an acquisition records which entities it covered — including the ones
the source had no answer for, so they are not asked again — and a snapshot is
reproducible from the model that made it.

## Modules

- **`app/`** — everything domain-specific: the Wikidata extraction and generation
  pipeline, the transform layer, the Swing workbenches, curation and the quiz code.
- **`objectview/`** — a **git submodule**
  ([ggyepesi/objectview](https://github.com/ggyepesi/objectview)): the generic
  object-rendering library. `Viewable`, cards, search, virtualization, media. It knows
  nothing about Wikidata and must stay that way.
- **`web/`** — the client: SvelteKit + Vite.
- **`docs/`** — design notes, worth reading before changing what they describe.

## Building

Java 21 and Maven. Always build through the reactor:

```bash
git clone --recurse-submodules https://github.com/ggyepesi/quiz.git
cd quiz
mvn -o -pl app -am test         # app + objectview suites
```

`mvn -pl app` on its own resolves objectview from your installed `~/.m2` jar, which can be
weeks stale — you get "cannot find symbol" errors in `app` that look like app bugs but are
a stale dependency.

## Running

There is no packaged launcher yet; run the entry points from your IDE against the
`app` module.

| | |
|---|---|
| `wikidata.explore.workbench.ModelBuilderMain` | the modelling workbench |
| `quiz.transform.app.TransformApp` | curation and transforms over a generated snapshot |
| `quiz.web.ViewableServerMain` | the API for the web client — port 7070, first argument overrides |

The client is a separate process:

```bash
cd web && npm install && npm run dev
```

See [docs/serving-the-web-app.md](docs/serving-the-web-app.md) for the order to start
things in, and for running it over a LAN or a tunnel.

## Reading further

- [docs/modelbuilder-constructs.md](docs/modelbuilder-constructs.md) — the conceptual
  anchor: class, statement, selection, identity regimes, membership.
- [docs/discovering-and-naming-a-vocabulary.md](docs/discovering-and-naming-a-vocabulary.md)
  — one construct end to end, from searching Wikidata to a named value domain.
- [docs/nobel-prizes-first-release-guide.md](docs/nobel-prizes-first-release-guide.md) —
  a full domain configured step by step, with the counts each step should produce.
- [docs/sparql-generation-rules.md](docs/sparql-generation-rules.md) — the WDQS
  heuristics the query builders follow, and why each exists.

## Status

Working software under active development, not a released product. The domains above are
generated from their saved models; which of them the web client sees is a separate
dataset registry, and the first release is still being finished. Interfaces change without
deprecation cycles.

## Licence

[Apache-2.0](LICENSE).
