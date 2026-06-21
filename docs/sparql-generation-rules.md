# SPARQL generation rules (WDQS)

Heuristics learned while tuning the Wikidata/DBpedia query generation. They are
written as **descriptions now**; the intent is to grow this list and later
encode it as a programmatic rule set applied/linted by `RuleNodeQueryBuilder`
(and friends) when it emits SPARQL.

Format per rule: **Trigger** (when it applies) · **Rule** (what to do) ·
**Why** (the evidence) · **Hook** (where it would be enforced in code).

---

## R1 — Limit DISTINCT entities, not result rows
- **Trigger:** a query returns up to N entities but also fetches a *multi-valued*
  field (e.g. `P1215` apparent magnitude — a star has several).
- **Rule:** aggregate the multi-valued field (`SAMPLE` = one value, `GROUP_CONCAT`
  = the list) and `GROUP BY` the entity; put the `LIMIT` on the grouped result.
  Never put `LIMIT` on the raw rows.
- **Why:** a row `LIMIT` cross-products: `LIMIT 6` over a star with 4 magnitudes
  + one with 2 = 6 rows = **2 stars** (Antlia "2 instead of 6").
- **Hook:** `childQueryForParent` (grouped + `SAMPLE`).

## R2 — Never inner-`DISTINCT` subquery + an outer pattern on the entity var
- **Trigger:** `SELECT * WHERE { { SELECT DISTINCT ?e … LIMIT n } <outer triple
  on ?e> }`.
- **Rule:** keep it **one** query (grouped). Don't re-fetch fields in an outer
  block, and don't wrap with anything that joins on `?e`.
- **Why:** Blazegraph **flattens** the subquery and re-scans the whole candidate
  set, discarding the inner `LIMIT` → timeout. Measured: inner alone ~1s; with an
  outer triple/SERVICE on `?e` ~60s.
- **Hook:** `childQueryForParent`, `valuesQueryForNode`.

## R3 — Label INLINE, not via `SERVICE`, for big-class subqueries with a `LIMIT`
- **Trigger:** labelling a subquery that has a `LIMIT` over a large class.
- **Rule:** use inline `?e rdfs:label ?l . FILTER(LANG(?l)="en")` inside the
  subquery. Reserve `SERVICE wikibase:label` for small/whole-result labelling.
- **Why:** `SERVICE` is a special form of R2 — it flattens the subquery and
  re-scans (Q523 ~3M → 60s). Inline label streams *with* the `LIMIT` (~1s).
  Also: `SERVICE` in **automatic** mode only emits `?xLabel` for vars named in
  `SELECT`; with `SELECT *` it emits **nothing** — must bind manually.
- **Hook:** `valuesQueryForNode` (`useService=false`), `labelService` (manual mode).

## R4 — A required label is a selectivity lever
- **Trigger:** a popular relation (e.g. `P59` → a constellation's ~20–30k catalog
  objects) where most targets are unnamed catalog dumps.
- **Rule:** when the node requires a label, enforce it **inline** as a hard
  `FILTER(LANG=…)`; it both restricts the scan to *named* entities and labels.
- **Why:** ~2s (named only) vs ~60s (all). Also fixes "renders as bare QID".
- **Hook:** `childQueryForParent` (inline label when `requireLabel`).

## R5 — Numeric range filters are NOT index-backed
- **Trigger:** `FILTER(?v <= N)` on a quantity (magnitude, area) over a large set.
- **Rule:** don't rely on the filter to be selective — it scans. Bound the scan
  with another constraint (a selective relation, or a required label). Avoid
  `xsd:decimal(?v)` casts — they further defeat any index.
- **Why:** the root Star query over Q523 + `magnitude<=6` scans ~3M regardless of
  the threshold; only the label/relation bounds it.
- **Hook:** value-filter emission; a future "needs a bounding constraint" lint.

## R6 — Counterintuitive: a *looser* threshold can fill a `LIMIT` faster
- **Trigger:** scanning for the first N rows that pass a range filter.
- **Rule:** don't tighten a filter to "go faster". A looser bound means more rows
  qualify early, so the streaming `LIMIT` stops sooner.
- **Why:** `magnitude<=6` fills `LIMIT 200` faster than `<=4` (fewer qualify).
- **Hook:** guidance/recipe text, not codegen.

## R7 — Membership exactness drops subclass-typed entities
- **Trigger:** `?e wdt:P31 wd:Qx` membership.
- **Rule:** prefer **specific** additional type QIDs (multi-QID `VALUES`) over a
  recursive `P31/P279*`. Drop membership entirely on an edge when another
  constraint (relation + magnitude + label) already implies the type.
- **Why:** `P31=Q8928` misses Aries (= *zodiacal constellation*); `P31=Q523`
  misses Alpha Antliae (= *variable star*). `P279*` is **over-broad** (Q8928 → 284,
  incl. Chinese/former/gamma-ray) AND **times out** over big classes (stars).
- **Hook:** `additionalSourceQids` (root), `EdgeMembershipMode.NONE` (edge).

## R8 — Per-parent vs one batched query
- **Trigger:** loading a child edge across many parents.
- **Rule:** use **per-parent** queries when each parent's candidate set is small
  and a per-parent `LIMIT` is needed. A single batched (`VALUES` of parents)
  query can't express a per-parent limit and pulls everything.
- **Why:** per-parent constellation→stars is ~7–18s each; the batched form over
  all constellations' stars times out.
- **Hook:** `RuleTreeExtractor.loadEdgeBatched`.

## R9 — Language fallback `"en,mul"`, but `mul` is overloaded
- **Trigger:** names that live only in the multilingual (`mul`) label.
- **Rule:** request `"en,mul"` so e.g. Q14044 → "Albaldah" resolves. BUT for a
  *"named entity"* filter prefer **en-only** — `mul` also holds catalog
  designations (e.g. `2MASS …`), so `en+mul` lets catalog junk back in.
- **Why:** Q14044 has no `en` label, only `mul` = "Albaldah"; but faint catalog
  stars also carry `mul` designations.
- **Hook:** `labelService` (fallback), inline label `FILTER` (en-only).

## R10 — A root over a huge class (≈3M) is inherently borderline on public WDQS
- **Trigger:** generating a root class whose membership is a very large type
  (Q523 "star").
- **Rule:** prefer reaching entities through a **selective relation** (e.g.
  Constellation→stars). Treat "all bright stars at once" as the hard case; expect
  ~40–60s even when correct, and consider a curated entry (WikiProject /
  sitelink) instead of `P31` membership.
- **Why:** no selective index for "magnitude range" over 3M; the streaming scan to
  find 200 named bright stars is ~40–60s cold.
- **Hook:** root membership; future WikiProject/sitelink source.

## R11 — An outer `ORDER BY` also flattens a big-class subquery
- **Trigger:** `SELECT * WHERE { { <subquery LIMIT n> } } ORDER BY ?x`.
- **Rule:** don't sort the extraction query over a large class. Drop the outer
  `ORDER BY` (let the inner `LIMIT` stream); sort downstream (the web
  `GeneratedSource` already sorts alphabetically). Or use a **named subquery**
  (`WITH … AS %r`) to keep the order without flattening.
- **Why:** same flatten as R2/R3 but triggered by `ORDER BY`: a notable-star root
  went **2s → 66s** purely from adding `ORDER BY ?valueLabel`.
- **Hook:** `sortAfterLimit` (now returns the subquery unsorted).

## R12 — A selective entry beats filtering a huge class (sitelink = "notable")
- **Trigger:** a root over a very large type where you want the *notable* subset.
- **Rule:** require an English Wikipedia article — `?a schema:about ?value ;
  schema:isPartOf <https://en.wikipedia.org/>` — as a selective entry. It bounds
  the class so the rest (magnitude, label) is cheap.
- **Why:** Q523 (~3M) → ~2886 notable; the notable-star root then completes in
  **~2s** (vs 38–66s) and returns famous named stars.
- **Hook:** `requireSitelink` (`appendSitelinkRequirement`); "Notable only" toggle.

## R13 — A named subquery prevents flattening but FORCES full materialization
- **Trigger:** wrapping a limited subquery in `WITH { … } AS %r` to keep an outer
  `ORDER BY` without flattening (the documented anti-flatten tool).
- **Rule:** only beneficial when the inner is **efficient**. The named subquery
  guarantees the inner runs to completion first — so it removes the early-stop
  streaming that hides a sloppy inner. Clean the inner first (no cross-product),
  else prefer streaming (R11).
- **Why:** notable-star root, ordered: **clean inner (constant membership, single
  `P1215`) = 2s; dirty inner (`BIND(…AS ?root)` + double `P1215` binding) = 51s**.
  The double-bind comes from one model field emitting BOTH a value filter and an
  included field on the same PID (e.g. `apparentMagnitude` ≤6 *and* the value).
- **Hook:** DONE — `sharedFilterVars` + `WikidataValueFilterSparql.appendWhereOnVar`
  dedupe the binding (root + child), so `sortAfterLimit` now uses
  `namedSubquerySort` (notable-star root: ordered, ~16s, flatten-free).
- **Corollary (done):** a value filter on the same property as an included field
  binds that field's var once — `?value wdt:Pxx ?f . FILTER(?f …)` — instead of a
  second binding.

---

## Cross-reference: the official WDQS optimization guide

[Wikidata:SPARQL_query_service/query_optimization](https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service/query_optimization)
is the authoritative source; several of our rules rediscover it, and it offers
tools we should adopt:

- **Named subqueries** — `WITH { SELECT … } AS %r WHERE { INCLUDE %r }` —
  *guarantees execution order and prevents cross-query optimization*. This is the
  **direct fix for R2/R3 flattening**: rather than inlining everything to dodge
  the flatten, a named subquery forces the inner `LIMIT` to run first, which may
  even let us keep `SERVICE` labelling (with `mul` fallback). **Worth testing as
  a cleaner replacement for our inline-everything workarounds.**
- **`hint:Query hint:optimizer "None"`** (or `hint:SubQuery …`) — force written
  pattern order when the planner picks a bad plan (another lever for R2/R3/R5).
- **`hint:Prior hint:rangeSafe true`** — for type-safe range predicates (R5):
  prefer real range filters (`FILTER("…"^^xsd:dateTime <= ?d)`) over functions,
  and avoid `xsd:decimal()` casts.
- **Inverse property paths** (`?x ^wdt:P31/^wdt:P279* …`) and
  **`hint:Prior hint:gearing "forward"`** — selectivity for paths (relevant to R7
  if we ever need a path instead of multi-QID).
- **`SERVICE wikibase:mwapi`** (indexed search) instead of full-text label scans
  (relevant to R4/R10 — a possible alternative selective entry).
- **`COUNT(*)`** over `COUNT(?v)` for fast range counts; **`MINUS`** /
  **`OPTIONAL { } FILTER(!bound)`** over `FILTER NOT EXISTS` for set difference.

### Toward a programmatic rule set
Each rule has a **Hook** in `wikidata.explore.query.template.rule`. The plan is a
small linter/optimizer over the emitted query (or the `RuleNode`) that:
asserts R1/R2 (no row-LIMIT on multi-valued; no outer-pattern-on-subquery),
chooses inline-vs-SERVICE labelling per R3/R4, warns on unbounded range filters
(R5), and prefers multi-QID over `P279*` (R7). See also the memory note
`sparql_limit_distinct_entities_pattern`.
