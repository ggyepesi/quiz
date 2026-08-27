# Roadmap: History as the path to an open-source engine

> ## Status: PARKED — 26 August 2026
>
> Superseded for now by the **Rule Workbench Demo release checklist**, which makes a
> public vertical slice (Nobel + objectview 0.1.0) the forcing function instead of
> History. Nothing here is abandoned or wrong; it is waiting.
>
> **Why parked.** This roadmap's premise — exercise the consolidation against a real
> domain rather than extend it in the abstract — is the same premise the release
> checklist has. The checklist adds the one thing this document cannot supply: an
> outside reader who has to run the result. History cannot serve both roles at once,
> and Nobel is the better demonstration subject because its authoritative API and its
> enrichment sources have genuinely different responsibilities, which is the story the
> demo is about. History is Wikidata-only at v0 and cannot show that.
>
> **What History already proved, and what it cost.** Two engine gaps were found and
> closed by real use, which is exactly what a forcing domain is for:
>
> - a time qualifier was reduced to its year, losing both the day and the calendar it
>   was stated in — now `QualifierLoadConfig.Kind.DATE`, with the projection an
>   explicit, persisted model choice;
> - a date read through `wdt:` lost its calendar and precision entirely — now read
>   from the statement value node, best-rank, packed so one `SAMPLE` cannot pair one
>   statement's time with another's calendar, and translated by a Wikidata-owned
>   `CalendarModelCodec` so `FlexibleDate` stays a date rather than a Wikidata date.
>
> A third was closed to let the model exist at all: a source-class-less reify may now
> take its bounded value domain from a **seeded value class**.
>
> **Where it stopped.** The model validates, and it has been generated and saved:
> `total=167, Person=56, OfficeHolding=54, Position=1` (2026-08-26). Those counts are
> **pre-split**: the seeded Position then filtered statements as well as discovering
> holders, so only the Hungarian kingship became an OfficeHolding. Under the discovery
> /materialization split that followed, the same model should yield roughly 127
> holdings across a dozen positions. It has not been regenerated since. The snapshot is
> the proof the date work landed — **124 values carry `(Julian)`**, and precision
> survives beside it: Stephen I is born `969 (Julian)` at year precision and dies
> `1038-08-15 (Julian)` on the stated day. A run log is beside it.
>
> - `Position` seeded `Q6412254` (Apostolic King of Hungary) is the population entry;
> - `OfficeHolding` reifies `P39` against it — verified to derive `[Q6412254]` as its
>   allowed value domain, with `startDate`/`endDate` loading as `DATE` and
>   `predecessor`/`successor` (`P1365`/`P1366`) as per-reign qualifiers;
> - `Person` carries no population and fills purely by reference.
>
> **Known open questions**, recorded so they are not rediscovered:
>
> - `extends` cannot express `Ruler` here: a subclass inherits its base's population
>   and cannot declare its own, so the only base that makes the subclass a *narrowing*
>   is "all humans", which then generates 200 strangers. It becomes usable when the
>   population is legitimately "people with a reign" — i.e. once a second realm exists.
> - The member bit is per **class**, not per instance, so a single `Person` class
>   cannot distinguish the served rulers from referenced relatives. That is why
>   `father` is absent from v1.
> - A field created as `AUTO` and later switched to `DATE` keeps the `YEAR` qualifier
>   projection, because "never chosen" and "chose YEAR" are deliberately the same state
>   on disk. History hit this; check the setting on any date qualifier.
>
> **When resuming**, read this status block, then
> [`domains/history/decisions.md`](../domains/history/decisions.md), then Milestone 1
> below. The milestones are still the intended order.

## Direction

The long architectural consolidation should now be exercised rather than extended in
the abstract. The next development cycle builds a deliberately bounded History domain.
Whenever that domain exposes a missing capability, we decide explicitly whether to:

1. use an existing open-source project or native source API;
2. adapt a capability that already exists in this repository; or
3. implement a new provider-neutral feature.

The History domain is the forcing function. The reusable source-to-snapshot engine is
the product that emerges from satisfying those real needs without putting History,
Wikidata, ModelBuilder or Swing into its contracts.

This roadmap complements:

- [`open-source-boundary.md`](open-source-boundary.md), which defines the intended
  engine boundary;
- [`domains/history/README.md`](../domains/history/README.md), which defines the first
  demonstration domain.

## Working rules

### History first, generalization second

Do not build a general capability merely because History might need it. First identify
one concrete field, population, relationship or evidence scenario. Implement the
smallest reusable capability that satisfies it, then verify it against an existing
domain where applicable.

### No domain-specific pipeline branches

No production code should ask whether the domain is `history`, whether a class is
`Person`, or whether a field is a particular PID. History-specific facts belong in its
model and source recipes. A new engine construct must be expressed through the same
provider, binding, plan and reporting contracts available to every domain.

### Reuse is a recorded decision

For each new source or parsing need, add a short decision entry containing:

- the concrete History scenario;
- native APIs and open-source projects considered;
- license, maintenance, language/runtime and packaging implications;
- semantic fit and important limitations;
- whether we adopt, wrap, use as a test oracle, or decline each candidate;
- the smallest experiment that supports the decision.

An existing project is not automatically a runtime dependency. It may be more useful as
a reference implementation, a conformance oracle, an optional provider or a source of
published mappings.

### One explained plan is one executed plan

Every external operation must be visible in the compiled plan before it runs. Generate,
Enrich and later headless execution consume that same immutable plan. A newly integrated
library does not get a private network or execution path around progress, cancellation,
retry, checkpointing, quality or yield measurement.

### Preserve the evidence boundary

External library objects may live inside their provider adapter. Persistent snapshots,
evidence records and public engine contracts remain provider-neutral and structurally
serializable.

## Work streams

Three streams advance together, but milestones below define their order.

| Stream | Purpose |
| --- | --- |
| History | Produce an understandable, useful demonstration domain |
| Engine | Turn each real need into a reusable source-to-snapshot capability |
| Open-source readiness | Establish headless use, documentation, fixtures and a stable boundary |

The History stream leads. Engine work is admitted by a demonstrated need. Extraction
work follows only when the exercised boundary is clear.

## Milestone 0 — Close and baseline the consolidation

### Goal

Establish a trustworthy starting point before History changes the model or pipeline.

### Work

- Commit the boundary, History brief and this roadmap after review.
- Run the focused datasource, generation, snapshot and workflow suites.
- Generate Movies and Oscar Nominations from a clean application restart.
- Preserve representative execution artifacts for both domains:
  - compiled-plan summary;
  - root and final-object counts;
  - final quality;
  - source yield;
  - representative evidence lineage;
  - checkpoint/resume result.
- Record known acceptable variation separately from semantic invariants. Wall-clock
  time and request order are measurements, not golden output.

### Exit gate

Both domains load, generate, save and reopen; their semantic baselines are recorded; no
known P1/P2 regression remains in the refactored path.

## Milestone 1 — History Explore notebook

### Goal

Discover the real data shape before creating abstractions or a full model.

### Scope

Use a small sample around the Revolutions of 1848:

- 3–5 events;
- 10–20 people;
- 3–5 places;
- 2–5 historical documents;
- the statements connecting them.

### Work

- Explore candidate Wikidata population relations.
- Inspect participation and office-holding statements, ranks and qualifiers.
- Record all value classes seen in participant, office, place and document roles.
- Discover available Wikipedia sitelinks, categories, templates and infobox parameters.
- Check whether candidate primary documents have usable Wikisource editions.
- Record missing/conflicting facts and examples where provenance matters.
- Create `domains/history/decisions.md`; one entry per retained or rejected construct.

No new general feature is implemented during discovery unless Explore itself cannot
observe a required source shape. In that case the feature is limited to discovery and
does not silently become generation semantics.

### Exit gate

We can describe the first model with observed source properties and representative
entities, and can name the first multi-source fact that adds demonstrable value.

## Milestone 2 — History v0: Wikidata-only vertical slice

### Goal

Generate, save and inspect the smallest coherent History snapshot using existing
capabilities.

### Initial model

- `Person`
- `HistoricalEvent`
- `Place`
- one relationship requiring a statement record (`Participation` or `OfficeHolding`)
- only the vocabularies and owned structures necessary for those classes

Historical documents and secondary sources remain outside v0 unless the observed model
cannot tell a coherent story without them.

### Work

- Configure the model entirely through ModelBuilder.
- Preview the compiled population and field acquisition plan.
- Generate a small bounded population.
- Verify statement construction, incomplete qualifiers, kind classification,
  canonical references and owned components.
- Open the saved domain in TransformApp without domain-specific adapters.
- Add representative lineage fixtures for one scalar, one reference and one qualified
  relationship.

### Exit gate

The vertical slice generates head-to-tail using existing public configuration concepts.
Any missing architectural concept is documented with a concrete instance before work
starts on it.

## Milestone 3 — General MediaWiki page acquisition

### Forcing scenario

History needs one Wikipedia fact or piece of evidence whose value is absent or
insufficiently explained in the Wikidata-only snapshot.

### Goal

Factor the existing Wikipedia-specific paths into a reusable MediaWiki provider
capability shared by Wikipedia and later Wikisource.

### Native/open-source evaluation

- Prefer MediaWiki `action=query` for revisions, redirects, categories, templates,
  links and source content.
- Prefer MediaWiki `action=parse` for authoritative rendered text/HTML when expansion
  matters.
- Retain the existing batching, concurrency, logging, retry and checkpoint machinery.

### Work

- Define a provider-owned page demand: site, titles and required page capabilities.
- Coalesce compatible metadata/content demands into batched requests.
- Retain revision, URL, digest, retrieval time and answered-capability coverage.
- Make Wikipedia category and infobox acquisition consume this common result.
- Expose source yield per capability and per bound field.
- Re-test one Movies example (such as location/category evidence) as the cross-domain
  proof.

### Exit gate

Wikipedia categories and infobox discovery no longer own duplicate page-fetch logic;
the History fact and the Movies regression use the same MediaWiki acquisition feature.

## Milestone 4 — Choose an infobox parsing strategy by measurement

### Forcing scenario

The History sample contains an infobox structure or value that the current lightweight
parser does not represent correctly or cannot explain.

### Goal

Select parsing technology from evidence rather than preference.

### Experiment

Build a versioned 30–50-page corpus drawn from History, Movies and Oscar Nominations.
Compare:

- the current Java extractor;
- `mwparserfromhell` as a reference/test oracle;
- MediaWiki's rendered parser output;
- Sweble only if a current Java-runtime spike is viable.

Measure top-level template detection, parameter names, raw values, nested-template
handling, resolved links, failure rate, runtime and packaging cost.

### Possible outcomes

- Keep and harden the lightweight parser if it is sufficient.
- Adopt a Java parser behind `WikitextStructureExtractor`.
- Use `mwparserfromhell` only as a conformance oracle.
- Use server-rendered MediaWiki output for expanded values while keeping raw parsing
  for parameter discovery.

### Exit gate

The chosen strategy has a recorded decision, a regression corpus and a replaceable
provider-internal interface. No parser-specific object reaches the snapshot.

## Milestone 5 — First meaningful secondary-source feature

### Goal

Demonstrate that a class can combine sources without hiding precedence or provenance.

### Work

- Configure one or two measured Wikipedia category/infobox bindings in History.
- Show preview, acquisition, review/apply and final lineage through the shared workflow.
- Compare yield, conflicts and unique contributions against Wikidata.
- Make source-specific selection available for auditing affected field values.
- Reject bindings with negligible value rather than accumulating decorative sources.

### Optional DBpedia experiment

If a raw infobox parameter needs semantic normalization, evaluate DBpedia's published
infobox mappings as correspondence suggestions. Present mappings for explicit user
acceptance; do not silently replace raw discovery. Running the Scala extraction
framework is not required merely to consume its mappings or published results.

### Exit gate

At least one History value visibly benefits from the second source, carries complete
lineage and remains correct after save/reload and Enrich.

## Milestone 6 — Wikisource primary-document experiment

### Forcing scenario

One modeled historical document has a stable Wikisource edition capable of
corroborating a date, author, association or quoted fragment.

### Goal

Prove that the MediaWiki provider is a site-neutral capability and that primary text is
represented differently from an encyclopedia-derived field value.

### Work

- Configure Wikisource as another MediaWiki site, not a copied provider.
- Acquire edition/page metadata and versioned text.
- Discover or configure document-to-entity correspondence explicitly.
- Attach an evidence fragment that corroborates an existing conclusion.
- Keep `CORROBORATE` distinct from a candidate that can overwrite a field.

### Exit gate

The same MediaWiki acquisition implementation serves Wikipedia and Wikisource; the UI
can explain the different evidential roles of their outputs.

## Milestone 7 — WDTK evaluation inside the Wikidata provider

### Goal

Determine whether Wikidata Toolkit should become the sole Wikibase decoder without
replacing the engine's execution semantics.

### Work

- Decode saved `wbgetentities` fixtures through WDTK.
- Adapt labels, aliases, sitelinks, statements, snaks, ranks and qualifiers into the
  provider-neutral fact/evidence representation.
- Compare semantic output, parse time and peak memory against Movies, Oscars and History.
- Project demanded properties immediately rather than retaining duplicate WDTK graphs.
- If the result is clean, replace hand-written Wikibase interpretation behind the
  provider adapter.

### Later option

Evaluate `wdtk-dumpfiles` as a separate acquisition operation only when a domain scale
or reproducibility need justifies processing a complete dump.

### Exit gate

Adopt or decline WDTK with measured reasons. If adopted, no WDTK class appears in the
public engine or saved snapshot format.

## Milestone 8 — Headless source-to-snapshot facade

### Goal

Make the exercised engine usable without ModelBuilder or TransformApp.

### Work

- Introduce read-only compilation producing an immutable explained plan.
- Execute that exact plan through a headless generation context and progress sink.
- Return snapshot, quality, source yield, lineage, execution record and checkpoint
  metadata as one generation result.
- Make ModelBuilder an adapter/client of this facade, not a parallel execution path.
- Run History from an automated test or CLI without constructing Swing.

### Exit gate

A clean checkout can generate the bounded History example headlessly, and the pipeline
diagram can be reconstructed from its saved execution artifact.

## Milestone 9 — Extract and publish the first open-source module

### Goal

Turn the now-proven boundary into a maintainable public component.

### Work

- Move the dependency-closed contracts and implementation identified by the headless
  facade into a separate module first; use a separate repository only if release and
  contribution workflows warrant it.
- Remove UI, quiz and legacy-model dependencies from its runtime graph.
- Publish provider-authoring and example-domain documentation.
- Include the bounded History configuration and representative fixtures.
- State experimental versus stable APIs explicitly.
- Choose a name only after the public responsibility can be explained without internal
  project terminology.

### Exit gate

An external user can understand, build and run the History example; add a provider
without editing engine code; and inspect why the resulting snapshot contains a value.

## What not to do yet

- Do not model all of nineteenth-century history.
- Do not extract packages solely to make the tree look cleaner.
- Do not create a generic query language spanning SPARQL, MediaWiki and SQL.
- Do not add sources without a measured contribution to a configured field or evidence
  scenario.
- Do not replace proven checkpoint/cache/retry machinery merely because a library can
  make the underlying HTTP call.
- Do not publish unstable UI-era model classes as the open-source API.
- Do not make History wait for the final module boundary; it is how that boundary will
  be discovered.

## Immediate next actions

**Parked — see the status block at the top of this document.** The actions below are
what this roadmap would resume with, not what is being worked on now.

1. Read the existing run first: `history.counts.tsv` and `query-log-history-1.txt`
   record what the one generation produced, and the snapshot already shows the date
   work landed. Re-run only to compare counts, not to find out whether it works.
2. Complete Milestone 0's semantic baseline for Movies and Oscar Nominations.
3. Continue the bounded History Explore sample (Milestone 1).
4. Stop after discovery and review the observed model before extending History v0.

Current work is the Rule Workbench demo release: a public vertical slice over the Nobel
domain with objectview 0.1.0. The engine capabilities History forced are already in the
main line and are not affected by this pause.

This order deliberately puts the next user-visible domain ahead of further extraction,
while making every new capability a candidate part of the eventual open-source engine.
