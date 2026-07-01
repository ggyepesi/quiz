# Source-provider interface — design discussion log

*Parked idea (not started). Recorded 2026-06-27. This is a verbatim-in-spirit log
of the discussion so the reasoning travels with the idea when we pick it up.*

## The idea (user)

> Can we factor out all the source-related tasks and define an interface for them
> so that everything could work when plugging in a new implementation of the
> interface? I think this would involve defining a kind of language for
> formulating the requests against the sources.

Later clarification:

> I thought it might be usable for specific SQL databases, for example.

Intent: one `SourceProvider` interface that every source-related task goes
through, plus a **source-agnostic request "language"** each provider translates to
its own backend. This makes "the L in ELT" pluggable — a new backend (another
knowledge graph, a REST entity API, a **SQL database**, a local KG) implements the
interface and immediately works everywhere.

## First opinion on value (assistant)

Architecturally sound and the correct *eventual* shape, but value is **conditional
on a concrete near-term need for a structurally-different source**. Three cautions:

1. **Designing against ~1.x sources.** Wikidata is the one real backend; DBpedia
   is a half-backend that is *also* SPARQL (same dialect). An interface designed
   when the only implementation is SPARQL will likely leak when the first
   genuinely different source arrives. Abstractions need two *different*
   implementations to be trustworthy.
2. **The request language tends to reinvent SPARQL.** Sources differ in
   *capability*, not syntax. Wikidata is a full graph (multi-hop membership,
   qualifiers, property paths); flat-card APIs (SerpAPI / Google KG) have almost
   no relational query. A neutral language either collapses to the lowest common
   denominator (throwing away what makes Wikidata good) or grows rich enough to
   express qualifiers/multi-hop/value-type — at which point it *is*
   SPARQL-with-capability-flags and the flat providers implement 10% and decline
   the rest.
3. **The real pain today isn't pluggability.** Nothing is blocked by "can't add a
   source." The genuine mess is that request knowledge is scattered —
   `FieldSourceMapping` + `RuleNode` + `QualifierLoadConfig` are three fragments
   of the same implicit request model. That's a *coherence* problem worth fixing
   regardless.

### Split the idea in two

- **(A) Unify the request model** — fold those three fragments into one coherent
  "what I'm asking a source for", still Wikidata-only. **Worth doing semi-soon**;
  pure internal win, de-risks everything else, pays off even with one source.
- **(B) Provider interface + multiple backends** — **defer until a concrete
  second, different source exists, and let that source drive the design.**
  Building it speculatively is the classic way to get an abstraction that's wrong
  in the expensive direction.

### The one design guardrail

Put the seam at the **task** level — "give me the population of this class",
"give me these fields + qualifiers for these entities" — **not** a neutral query
AST that every provider compiles. The WDQS tuning (R1–R15, the #67
SAMPLE/distinct-entity-LIMIT fix, child batching, named-subquery sort) is the
crown jewel and is entangled with SPARQL construction. A task-level seam lets the
Wikidata provider keep its bespoke, hand-tuned SPARQL builder *inside*. A
query-AST seam would force regenerating SPARQL from a neutral model and risk
regressing all of it. That distinction is the whole game.

## How the SQL-database angle changes the assessment (assistant)

SQL strengthens the case **in favour**. A SQL DB is the rare combination of
**structurally different but still query-capable** — joins, filters, aggregation —
so the request language stays meaningful instead of degenerating to LCD (the
SerpAPI worry). Two *capable but different* backends (a triple graph and a
relational DB) are exactly the conditions under which the abstraction becomes
trustworthy. This moves **B from "speculative" toward "justified"**.

It also raises the value **ceiling**: a SQL provider makes the framework usable on
**private/proprietary relational data**, not just public KGs. "Any company's
database becomes a quiz domain" is a categorically bigger proposition than "one
more public KG."

But it relocates the hard part — two net-new cost centres:

1. **Schema binding.** Wikidata is self-describing (universal PIDs/QIDs, a label
   service, one global schema). A SQL DB is bespoke: you must declare "population
   = table X filtered by Y; field Z = column C; reference R = FK join to table
   T." That per-source mapping is what `FieldSourceMapping` only gestures at, and
   it's real (but reusable across any SQL source).
2. **Identity.** Nearly everything in the pool hangs off QIDs — dedup, canonical
   objects, cross-refs, the Source chip, name-collision detection
   (`WikidataObjectRegistry`, canonical-by-qid). SQL rows have primary keys, not
   global identifiers. You'd need an identity notion mapping PK → pool identity.
   Tractable, but it would otherwise surface mid-build as a nasty leak — good it
   shows up now.

SQL **reinforces the guardrail**: you'd never compile a generic AST to SQL (each
schema differs), so the SQL provider must own its query generation + schema
binding internally, exactly as the Wikidata provider keeps its WDQS tuning
internal. Task-level seam fits both; AST-level seam fits neither.

## Shape to aim at (when we start)

- A `SourceProvider` interface capturing the capabilities the pipeline needs:
  resolve a **membership/population**, fetch **scalar/reference fields**, fetch
  **child edges**, fetch **statements + qualifiers**, **labels/images**, maybe
  **search/sample**. With **capability negotiation** so a provider can decline
  what it can't do (a flat REST source has no qualifiers; SQL has no qualifiers
  but does have joins).
- A declarative, source-neutral **request model** (the "language"): Membership
  spec (relation + target type, possibly multi-hop like Oscars `received →
  instanceof Q19020`), FieldRequest (property, direction, cardinality, value-type
  constraint, qualifiers), batching/limit/label hints. Today's `FieldSourceMapping`
  + `RuleNode` + `QualifierLoadConfig` are de-facto fragments — unify them.
- A per-source **schema binding** layer (needed by SQL, near-trivial for
  Wikidata) and a per-source **identity** mapping (PK ↔ QID-equivalent).
- Backend-specific tuning lives **inside** each provider.

## Effort estimate

- **Part A (unify request model, Wikidata-only):** ~**3–6 focused days**, mostly
  careful regression testing against existing domains (constellations, mythology,
  elements, oscars) so the tuning doesn't regress. Worth doing first.
- **Part B (provider interface + a real second backend + capability
  negotiation):** ~**2–4 weeks**. Bigger with SQL because schema-binding and
  identity are net-new sub-problems, not just "translate to another API". Expect
  the request language to need **2–3 iterations** as the second source exposes
  leaks. Payoff ceiling grows more than the cost does **if a specific SQL target
  is real**.

## Verdict

Green-light **A** opportunistically (pays for itself in coherence). Gate **B** on a
real second source — and a **specific SQL database you'd want as a domain is the
best forcing function** for the design, better than any web API. When that's real:
let the database's schema drive the request language; don't design it abstractly
first.

## "Public tool for others' databases" framing (assistant)

User clarified there's no specific database in mind — the motivation is that the
framework could be a general public-domain tool **others** run on **their**
databases. Assessment: this is a **product/adoption bet, not a technical need**,
and it's a *weaker* forcing function, not a stronger one — "others might use it"
constrains the interface nothing, whereas a specific schema would expose every
leak. Designing against zero real second implementations AND zero real users is
the most speculative version.

Key points:
- **Validate a plugin API by writing plugins, not publishing an interface.**
  De-risk by dogfooding: do A, then write the first SQL provider yourself against
  a real public dataset (open SQL/CSV data is plentiful). That second real
  implementation proves the seam. This path is *identical* whether or not the
  public-tool dream pans out, so A stays the no-regrets move.
- **If "public tool" is the goal, the moat isn't the source interface.** "Expose
  your DB to an app" is commoditized (BI / low-code / ORMs). The differentiator is
  everything *above* the source: the modeling workbench, the ELT transform/reify
  constructs, the quiz/learning UX. The source abstraction is enabling plumbing,
  not the headline — weight investment accordingly.

**Resolution (user, 2026-06-27):** understood and convinced; the idea remains
parked in this log; returned to current work (Oscars event-root model).

## Related

`FieldSourceType` (WIKIDATA/DBPEDIA), `FieldSourceMapping`, `RuleNode`,
`QualifierLoadConfig`, `DBpediaEnrichment`, the `wikidata.explore.query.logical.*`
classes, `docs/sparql-generation-rules.md` (R1–R15), the #67 grouped-query fix.
This is really "make the L in ELT pluggable" (see `docs/build-log.md` ELT
section).
