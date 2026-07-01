# Quiz build log — domains, generation, ELT, search & UI

A running journal of the quiz project: Wikidata domains (mythology, constellations,
periodic table, Oscars), the generation pipeline, the Load/Transform/ViewConfig
(ELT) architecture, search, and UI. We append **Steps**, **Decisions**,
**Lessons**, and **Open questions** as we go. Started with Greek mythology
(2026-06-23).

---

## Goal

A Greek-mythology quiz domain: characters (gods, heroes), their relationships
(genealogy), and curated sets (Olympians, Labours, Argonauts). Served via the
existing generated→web seam.

## Known facts (probed live)

**Membership types** (what to set as a class's Wikidata type / P31):

| Concept                 | QID         | Instances |
|-------------------------|-------------|-----------|
| Greek deity             | Q22989102   | 327       |
| Olympian god            | Q113103481  | 12        |
| goddess (all myths)     | Q205985     | 564       |
| war deity               | Q41863069   | 87        |

**Curated sets** (via Explore-by-example → Seed QIDs):

| Set                 | Anchor QID | How it's modelled            |
|---------------------|------------|------------------------------|
| Twelve Olympians    | Q101609    | has part (P527), 12          |
| Labours of Heracles | Q1233460   | has part (P527), only 6/12 ! |
| Argonauts           | Q165510    | member of ← (P463), 22       |

**Useful properties (fields)** seen on Athena (Q37122):
`image` P18, `father` P22, `mother` P25, `child` P40, `spouse` P26,
`part of` P361 (e.g. the Olympians), `worshipped by` P1049, `residence` P551,
`sex or gender` P21, `depicted by` P1299.
Genealogy (P22/P25/P40/P26) is the densest, best quiz material.

## Recommended approach

1. **Characters via membership type, not WikiProject.** Class `GreekDeity`,
   Wikidata type = **Greek deity (Q22989102)**. (WikiProject "Mythology" is
   broad/mixed; the P31 type is the clean set.)
2. **Genealogy as self-referential edges.** father/mother/spouse/child point at
   other `GreekDeity` → a relationship graph (great for "who is X's father?").
3. **Curated sets as seed classes.** e.g. `Olympian` seeded from Q101609's
   has-part, via Explore → Add set as Seed QIDs.
4. **Narrative deeds are sparse** — accept gaps or use the Wikipedia-category
   fallback later (Labours: Wikidata has 6/12, the enwiki category has all).

## Suggested first steps

1. New domain → "Greek mythology".
2. Class (rename root to `GreekDeity`): set Wikidata type = Greek deity
   (Q22989102); maybe tick **Notable only** to trim; limit ~350.
3. Add fields: `image` (P18), `father` (P22), `mother` (P25). Generate at
   depth 0 first to sanity-check counts, then add genealogy edges.
4. Save domain.

---

## Steps taken

- **S1 (2026-06-23):** Class `Character` with membership type **Q22988604
  "mythological Greek character" = 5,250 instances** (broad: gods + heroes +
  more). Added `father` (P22) via Discover with defaults → returns father
  *names* (labels), a scalar field. Next: turn `father` into a self-referential
  entity edge to `Character` + raise depth for the ancestor closure.
- **S2 (2026-06-23):** Generated `Character` with **Notable only + limit 2000 →
  1,989 characters in 6 s**, with `father`. Notable-only trims the 5,250 to the
  famous ones fast (sitelink selectivity, R12). Plenty for a quiz.
- **S3 (2026-06-23):** `father` is now a **linked `Character` entity** — clicking
  a father in the instances view scrolls to that character. Navigable genealogy
  graph works (in-set fathers; depth would pull non-set ones as child objects).

## Decisions

- **Membership = Q22988604 (5,250), not Greek deity (327).** Broader Character
  set. Large → trim with **Notable only** and/or a limit for a quiz.
- **Pacing (2026-06-23):** ship the simple `Character` domain end-to-end and
  verify on the web FIRST; defer curated sets / Labours / narrative deeds /
  closure-depth tuning until the basics are solid and checked.

### Do now (simple, end-to-end)
1. Finish core fields on `Character`: `mother` (P25, edge), `spouse` (P26) &
   `child` (P40) as collection edges, `image` (P18). Maybe a role/domain field.
2. Generate (Notable only, limit ~2000) → Save domain.
3. Restart web client → confirm `greekmythology` is served; check a few cards
   render (image + father/mother links navigate).

### Parked (until the above is checked)
- Curated sets (Olympians Q101609, Argonauts Q165510) via Explore → Seed QIDs.
- Labours / narrative deeds — needs Wikipedia-category fallback (#40).
- Closure-depth tuning / entity-edge suggestions (#41); Explore type/individual
  handling (#39).
- Heroes/creatures as separate classes.

## Lessons learned

- Narrative properties (deeds/labours) are sparse in Wikidata; concept-item
  P527 is often incomplete (Labours: 6/12). Prefer genealogy + curated sets;
  use the Wikipedia-category fallback for missing sets.
- WikiProject is broad/quality-based; for "all X of a kind" a Wikidata **P31
  type** is the clean route. WikiProject is a fallback when no clean type exists.
- Explore-by-example: a **group** entity → its members (P527/P463); an
  **individual** → use Discover (on the class) or open its QID, not the battery.
- **Don't Explore a TYPE/class concept** (e.g. Q22988604). The battery probes
  has-part/member-of, which a type doesn't have → you get nothing useful (just
  its own "part of"). For a type: set it as the class's Wikidata type and
  **Generate**; Explore is for groups (Olympians) and concept items (Labours).
- **Entity vs scalar fields:** a property added with defaults often comes back
  as the target's *label* (a scalar). To follow it as a relationship (and pull
  the target's own fields / a closure), make it an **entity edge to a class**
  (set the field's object type), then use **generation depth**.
- **Closure = self-referential edge + depth.** father (P22) → edge to
  `Character`; depth N = N generations of ancestors. Cycle-guarded (myth has
  cycles), so it's depth-bounded, not truly transitive. P22 stays in-class
  (969/1080 fathers are Characters), so the closure is meaningful.
- **Cardinality is sample-detected → can mis-fire.** father (P22) auto-became a
  `List` but mother (P25) a single, purely because the sample for P22 hit a
  multi-valued character and P25's didn't. In reality BOTH are multi-valued in
  myth (206 chars >1 father, max 6; 140 >1 mother, max 4 — conflicting
  traditions). Fix: **override cardinality to Collection** for genealogy edges;
  a single field that receives several values drops data / errors at generation.
- **"Show generated source" can look stale.** It preferred the last *generated*
  run's compiled source whenever the active class matched that run — so applied
  edits (a new field) didn't appear until you regenerated. Fixed: if the model
  changed since the last run (signature differs), it now shows a fresh preview
  from the current model ("preview — model changed since last generate").
  Otherwise: Generate to refresh. (Apply ≠ regenerate.)
- **`NoClassDefFoundError` at Generate = stale runtime classpath, not your
  edit.** Hit `NoClassDefFoundError: wikidata/explore/extract/StringUtils` even
  after reverting the change. The class exists and a clean `mvn clean compile`
  builds it into `target/classes`. Cause: the running ModelBuilder was launched
  on an old/incomplete classpath (e.g. an aborted IDE incremental build). Fix:
  Rebuild Project + relaunch from the fresh build. (A given edit can be the
  first to exercise the code path that loads the missing class.)
- **Discover already scans INCOMING relations** (`DiscoverClassPropertiesQuery`,
  direction column) — no need to guess PIDs; the incoming rows surface children
  (father-inverse P22), `depicts` P180, `main subject` P921, `killed by` P157,
  etc. Rare relations (labour episodes) may not rank across the whole 5,250
  class — discover from a narrower set. The #42 fix lets you actually USE them.
- **Non-unique names are real & significant:** 112 names shared among the 1,989
  notable characters (Agenor/Lycus/Thoas ×5). This is the Arethusa/Abas thing —
  same name, different QID. Generation now LOGS the collision list so it's
  explicit (`reportNameCollisions`). Quiz answers on these are ambiguous.
- **Exclude types** added (`FieldSourceMapping.excludedTypeQids` →
  `FILTER NOT EXISTS { ?value wdt:P31 wd:Qx }`; "Exclude types" field in the
  class panel). NB: excluding Roman deity (Q11688446) removes 0 — Greek
  characters aren't typed as Roman deities; use it for whatever non-Greek type
  actually appears.
- **Tool focus fix:** raising the main window after adding a field moved out of
  `afterApplyField` (which also fires on Discover's pre-run applyEdits and was
  stealing focus from the Explorer window) into an explicit
  `onFieldAddedFromTool` on non-AUTO adds only.
- **More events:** Episode = 15 only because Notable-only trims Q63143903's 59.
  Hierarchy: `episode in Greek mythology (59) → mythical event Q24336466 (98) →
  myth Q12827256 (133)`; sibling `mythical war Q24336068 (18)`. The broader two
  are PAN-mythological (not Greek). To broaden: drop Notable-only (59), "Also
  include types" `mythical war` Q24336068, and use **Discover subtypes** on
  `mythical event` to pick Greek-relevant subtypes (prune with Exclude types).
- **Wikipedia/DBpedia infobox is NOT a labour source either** (checked):
  `dbr:Labours_of_Hercules` has `owl:sameAs → Q1233460` but only
  `dbo:wikiPageWikiLink` (164 noisy links) — no structured infobox list (it's a
  list/table article, not an entity with an infobox). So no clean attachable
  field. Realistic sources stay: Category:Labours of Hercules (#40, adversaries)
  or the curated 12-QID seed (below). Use the seed — nothing beats it cleanly.
- **The 12 labours can't be queried cleanly — genuine Wikidata gap.** Max via
  ALL paths (P527 ∪ inverse P361 ∪ episode part-of) = ~7 deed-episodes; 4 deeds
  (Ceryneian Hind, Erymanthian Boar, Cretan Bull, Mares of Diomedes) have NO
  deed-entity. The full 12 exist only as ADVERSARIES in enwiki Category (14
  members) — a different representation. Options: incoming P361 (7 vs 6),
  hand-curate 12 via Seed QIDs (reliable), or #40 category source (adversaries).
  Decision: don't fight Wikidata; accept ~7 deeds or seed a curated 12.
- **Labours = the Episode class (Q63143903), linked by `characters` (P674).**
  `facet of` (P1269) is a dead end (only 2 of ~59 episodes use it). But the
  Episode type Q63143903 (59 instances; 15 Notable-only) CONTAINS the individual
  labours as standalone entities (Capture of Cerberus, Cleaning the Augean
  stables, …) — better than "Labours of Heracles" P527 (6/12). Connect episodes
  to characters via **P674 "characters"** (33/59 populated; P921=6, P710=7).
  Model: `Episode` class + `characters` (outgoing P674, list) → `Character`.
- **Only child-object EDGES become served types; inline entity values don't.**
  Snapshot evidence: constellations = 92 Constellation + 470 **Star** (stamped);
  mythology = 1996 Character + 1710 **(none)**. The extractor stamps types ONLY
  on child-object edges (`child.type(childNode.name())`); "related entity
  values" (DELAYED_ENTITY_FIELD: father/mother/child/facetOf) create UNTYPED
  references → `registerAll` never sees them as a type. So `facetOf` inline →
  no Episode panel. Making it "related objects" would stamp Episode but
  facetOf=P1269 links only ~2 episodes (useless). A single-root domain serves
  root + its child-edge types (Star under Constellation); two independent large
  sets (Character 1989, Episode 59, linked only by sparse Character→Episode
  P1269) can't both be top-level. FIX: Episode (Q63143903) and Labour as their
  OWN roots/domains (with `characters` P674 inline), not children of Character.
- **Child-edge objects (e.g. facetOf Episodes) only become a web TYPE after
  Save + web restart.** `extract()` returns roots only; child objects are
  embedded in the parent (Heracles.facetOf = [Episode…]) — so the ModelBuilder
  run shows NO standalone Episode panel (expected). `save()` flattens the graph
  into a flat qid pool (roots AND children, each type-stamped), and the web's
  `registerAll` serves every stamped type. So: Save domain → restart web → the
  Episode quiz type appears and facetOf renders. The web reads the SAVED
  snapshot only, never the in-memory run.
- **"Load as" glossary:** *simple property* = literal value (INLINE_VALUE);
  *related entity values* = target entities as references/QID+name, inlined, no
  depth (DELAYED_ENTITY_FIELD) — use for entity collections like
  father/mother/child/characters(P674); *related objects* = full sub-objects
  with own fields, separate per-parent query, needs depth ≥1 (CHILD_OBJECTS);
  *auto* = inferred. For "which characters are in this episode" use *related
  entity values*.
- **"Load as related object" (CHILD_OBJECTS) ≠ inline.** A field set to "Load as
  related object" with an object type (e.g. `List<Episode> facetOf`) becomes a
  child EDGE — queried in a SEPARATE per-parent query and ONLY when **depth ≥ 1**;
  it never appears in the root SPARQL. For a simple list of names inline (like
  father/mother/child) use **inline/reference** production instead (no depth, no
  object class needed; shows in the root query). This is why an incoming
  `facet of` (P1269) edge was absent from the root query — expected, not a bug.
- **Incoming (ITEM_TO_ROOT) fields were silently dropped from SPARQL** (#42,
  fixed). Modelling labours via incoming "facet of" (P1269) / "episode in"
  produced no pattern — `RuleIncludedField` carried no direction, so both emit
  paths hardcoded outgoing `?value wdt:P ?x`. Now direction-aware; incoming =
  `?x wdt:P ?value`. Needs a ModelBuilder rebuild/restart.
- **The two tools, crisply:** *Explore = text → QID* (a single QID to use as a
  class type/source, or — for a group/concept entity — its member set via the
  battery → Seed QIDs). *Discover = which relations/properties to add as fields*
  (samples what the class's instances actually have). "What's the QID?" vs
  "What fields can this class have?"

## Curated "12 Labours" seed set (deed-episode where it exists, else adversary)

Seed a `Labour` class (no Wikidata type, paste into Seed QIDs):
```
Q123241556 Q123419429 Q466882 Q334456 Q3088950 Q236429 Q746198 Q1048023 Q11813175 Q123443980 Q1140197 Q2937680
```
1 Nemean Lion Q123241556 (deed) · 2 Lernaean Hydra Q123419429 (deed) ·
3 Ceryneian Hind Q466882 (adv) · 4 Erymanthian Boar Q334456 (adv) ·
5 Augean stables Q3088950 (deed) · 6 Stymphalian Birds Q236429 (adv) ·
7 Cretan Bull Q746198 (adv) · 8 Mares of Diomedes Q1048023 (adv) ·
9 Girdle of Hippolyta Q11813175 (deed) · 10 Cattle of Geryon Q123443980 (deed) ·
11 Apples of the Hesperides Q1140197 (deed) · 12 Cerberus/Capture Q2937680 (deed).
Tradeoff: mixed label styles (deed phrases vs bare adversary names).

## Class-level importance ranking (DONE, #44)

Sort + limit are now **class-level** (not per-field): `FieldSourceMapping.rankBy`
(`""` none / `__sitelinks` / a number-or-date field name) + `rankDescending`,
kept to **top `limit`**. Compiler resolves it onto `RuleNode` (rankBySitelinks /
rankPropertyPid); the builder binds `?value wikibase:sitelinks ?rankMeasure` (or
`wdt:Pxxx`) and emits `ORDER BY DESC(MAX(?rankMeasure))` **inside** the limited
subquery (R11) so LIMIT keeps the top-N. UI: "Rank by" combo + "highest first"
on the class panel. Verified live: top-10 Greek characters by sitelinks =
Achilles/Aeneas/Agamemnon/Cerberus/Demeter/Helen/Medusa/Minotaur/Odysseus/Theseus.
Per-field "sort children by" retired. Compat: old models load (defaults none);
reconfigure rank + regenerate. **Next: #45** — intuitive field panel (What +
Source, infer Load-as/render/direction).

## Intuitive field panel (DONE, #45)

Field panel now reads conceptually, grouped **What** (Holds: Text/Number/Date/
Image/Entity · Of class · Count) · **Source** (From: Wikidata/DBpedia · Property ·
Found on: this/related) · **Refine** (Required · Numeric filter · "only related
items that are the chosen class"). Retired/inferred: **Load as** → AUTO (compiler
infers; entity ⇒ reference, scalar ⇒ inline); **Render mode** → inferred from
type; per-field **Sort + Limit** → class-level Rank by/Limit (#44); **Direction**
→ "Found on: this entity (outgoing) / related entities (incoming)"; **Edge
membership** → checkbox. Relies on #43 (canonical per-class generation) so
"type = a class" is purely a reference. Deferred: a "Detect direction" button;
honoring the membership checkbox on inline reference fields.

## Decision: genealogy stays by-reference (no closure)

father/mother/child = `List<Character>` inline references. A parent QID outside
the loaded set still shows its **name** (from the inline label) — good for quiz
answers — but is only navigable if that Character is loaded ("dangling"
otherwise). **No closure** (ancestors aren't pulled in as full nodes) — that's
deferred #41. **No cycle risk** with inline refs (single hop, no traversal);
if #41 is ever built it's cycle-safe (QID-canonical registry + depth bound).
Chosen 2026-06-24: ship top-N + name refs; revisit #41 only if a navigable
multi-generation tree is wanted.

## facetOf vs Episode.characters; Character.episodes

`facetOf` (incoming **P1269**) on Character is sparse and points at the
*collective* (Heracles → only "Labours of Heracles"). The rich link is
**`Episode.characters` (P674)** — per-episode ("Apples of the Hesperides" →
Heracles). So: **drop `facetOf` from Character**. For the navigable reverse
(Heracles → his episodes) add **`Character.episodes` = incoming P674** (episodes
whose `characters` include this one).

## Wikipedia-category source (DONE, #40)

New **"Category"** helper tab: enter a content category (e.g. `Labours of
Hercules`, `Twelve Olympians`), it reads the ns-0 article members and resolves
QIDs → **Add selected to Seed QIDs** / **Use as class type**. For sets Wikidata
under-models. `CategorySeedPanel` + `CategorySeedQuery`;
`WikiProjectCategoryReader` namespace-parameterized (ns 1 Talk for assessment,
ns 0 article for content). Verified: Labours of Hercules → 14 members → QIDs
(Nemean lion Q199438, Cerberus Q83496, …). NB the category gives the *adversary*
representation (Nemean lion, Cerberus the creature) + the collective + Augeas —
select the relevant ones.

## Constrain the other end of a reference field (DONE, #50)

A reference field (e.g. `Character.episodes` = incoming P674) was pulling ANY
entity with that property — for Arachne, the Hades II videogame + a novel, not
just episodes. Fixed: the "only related items that are the chosen class"
setting (edgeMembership INHERIT) now applies to inline reference fields too —
the compiler attaches the referenced class's membership to `RuleIncludedField`,
and both emit paths add `?valueVar wdt:P31 wd:<classType>`. Verified: Arachne's
videogame/novel dropped (constraint P31=Q63143903 → 0 false positives).
Limitation: single sourceQid, not additionalTypeQids (multi-QID follow-up).

## Source abstraction — collapse qid/wikidataUrl into a "Source" chip (DONE, #56)

Every card painted its raw `qid` row + `wikidataUrl` link at the top — noise.
Introduced a small provenance abstraction instead of just hiding them:

- `quiz.source.Source extends Quizable` (`sourceId()`/`url()`/`kind()`) +
  `quiz.source.WikidataSource` (QID + wiki URL, displayName "Wikidata",
  typeName "Source"). Different backends → different `Source` impls with
  different internals (future DBpedia/SerpApi), same interface.
- `WikidataDynamicObject`: `qid` + `wikidataUrl` marked `@NotQuizableField`
  (hidden from the panel only — Jackson/identity untouched); a new
  `@QuizableReference @JsonIgnore transient Source source`, built from the QID
  in the constructor / `qid()` setter. Renders as a collapsed
  **`source: Wikidata ▶`** chip that expands to the QID + clickable link.

Key constraint honoured: the **QID stays the canonical identity** (equals/
hashCode, snapshot pool, navigation, web serving) — `Source` is layered on top
as presentation/provenance, not a replacement. Derived from the QID, so it is
never persisted and survives save→restart because the flat-snapshot store
rebuilds objects through the constructor. Verified: visible fields go from
`qid wikidataUrl dynamicFields type` → `source dynamicFields type`; `source()`
= "Wikidata (Q34201)". Swing only for now; web (DynamicFields→JSON) unchanged.
Follow-up: mirror onto the generated typed-class codegen + the web client.

**Fix (same session):** the new `source` reference made `QueryObjectResultPanel.groupByType` (which walks reference fields and sections by `typeName()`) give `Source` its own third panel next to Constellation/Star. Fixed with a field-level annotation rather than a runtime `instanceof`: new `@quiz.annotations.Provenance` marks the `source` field; `QuizableAdapter.isProvenanceField` + the grouping's reference walk skips provenance fields, so a `Source` is never queued, never sectioned, never descended into. Annotation (not type check) so any future `Source` impl is covered and the owner declares the relationship. (Also stops a spurious 2-panel split on single-class domains.)

`@Provenance` now drives **both** behaviours from the one annotation: (1)
`QuizablePanel` renders an `@Provenance` field as a collapsed chip (like
`@QuizableReference`, which is dropped from the `source` field — one annotation
instead of two), and (2) the instances-panel grouping excludes/doesn't descend.
So the `source` field is just `@Provenance @JsonIgnore`. NB: a `mvn`-verified
load of the real constellation snapshot gives `source()=Wikidata (Q9256)` and
`visibleFields = source dynamicFields type` — so if a running ModelBuilder still
shows qid/wikidataUrl with no chip, it's a **stale build** (hot-swap keeps the
old class + cached reflected fields): needs a full Rebuild + JVM restart.

**Typed-class parity (codegen + mapper).** A generated domain shows *typed*
instances for any type with a generated class (mapper maps roots → `Character`/
`Episode`), and keeps cross-references whose type has no generated class as raw
`WikidataDynamicObject`. So Source only reached the dynamic ones at first —
symptom: top-level Achilles (typed) showed qid/url + no chip, but Achilles
reached as a reference from Episode (dynamic) showed the Source chip.
Fixed `GeneratedQuizableSourceGenerator` to emit `@NotQuizableField` on
qid/wikidataUrl + a trailing `@Provenance @JsonIgnore Source source`, and
`GeneratedQuizableMapper` to populate it (`new WikidataSource(qid, url)`). Now
typed + dynamic render alike. Requires **regenerate** (the typed class is
recompiled from the new template).

**Footer placement.** `source` declared LAST in both representations so the
chip renders as an unobtrusive footer below the real fields (verified order
`dynamicFields type source`), rather than at the top where the old qid row was —
fixes the layout the early placement disturbed.

## Query-log: show the failed step (DONE)

"Generate domain" reported FAILED but showed no failing step: `loadEdgeBatched`
records a per-parent child entry (`progress.subquery`) only on success; a
per-parent timeout threw before recording, aborting the run and leaving the
other parents firing as orphaned pool queries. Now each per-parent task
try/catches: a real cancellation (`InterruptedException`) still aborts, but any
other failure is recorded as a **FAILED child sub-query under the same log
entry** (`GenerationLog.subqueryFailed` → `LogStep.subqueryFailed` →
`WorkflowRecorder.addSubquery(..., LogStatus.FAILED)`) and the run continues
with partial data. So the failing step is visible/expandable, and one timeout
no longer aborts the domain or spawns orphans.

## Collapsible collections/maps in QuizablePanel (DONE, #54)

Complex collection/map fields now render under a clickable `field (N) ▶/▼`
header (`QuizableCollectionHeader`); the body is built only when expanded.
Default changed to **collapsed for all** (threshold 0) at the user's request.
Per-collection toggle remembered in `QuizableRenderContext`
(`isCollectionExpanded`/`toggleCollectionExpanded`/`setCollectionExpanded`,
keyed by the collection's identity). NB **`@QuizableInline` collections are NOT
collapsed** — that annotation means "always expanded" (e.g. the query-log step
tree); collapsing it hid the SPARQL/child steps behind a `steps (N)` header, so
the inline branch renders fully expanded as before.

**Regression fix:** a raw `WikidataDynamicObject` (e.g. a *loaded* snapshot —
Load shows raw objects; typed mapping only happens on Generate) keeps its fields
in a `dynamicFields` Map, which collapse was hiding behind a single
`dynamicFields (n)` header. Now the DynamicFields *container* map
(`value == df.dynamicFieldValues()`) is excluded from collapse and renders its
entries as before; only genuine value collections/maps collapse.

## FormStamper removed (DONE)

Dropped the unused compile-time form-view generator: pom `generate-form-views`
exec + `compile-generator-mojo` + the two `build-helper` add-source execs (and
re-pinned `maven-compiler-plugin` 3.11.0, which had been declared only inside
that block). Deleted `quiz.build.FormViewGeneratorMojo`,
`org.formstamper.core.engine.PathDrivenFormCompiler`,
`benchmark.generated.OscarNominationFormView`, `benchmark.CompiledEmulatorView`.
`BenchmarkRunner`/`VisualComparisonApp` now exercise **QuizablePanel only**.

## Search: reveal matches inside collapsed collections (REVERTED — needs redesign, #53)

Attempt 1 (reverted): a pre-pass in `addFieldHighlights` force-expanded
collapsed collections on matching paths (`QuizablePanel.expandCollectionsOnPath`
+ `QuizableRenderContext.setCollectionExpanded`) and `refresh()`ed those cards
mid-search, plus a deferred `scrollTo`. This **corrupted the card grid**: it
inserted apparent duplicate cards ("another one for each hit"), broke the
alphabetical order (e.g. Heracles after Odysseus), and showed a normally-collapsed
pane expanded without a title. Calling `refresh()` on cards during the search
pass (interacting with the search index / target-panel layout / shared render
context) is the suspected cause. Reverted to the prior stable search; the inert
helpers (`expandCollectionsOnPath`, `setCollectionExpanded`) are kept for a safer
redesign — likely make the collection render expanded at *card-build* time when a
search match lies inside it (no mid-search `refresh()`), rather than mutating and
refreshing cards during the highlight pass. Still open: collapsed `episodes`
matches aren't revealed, and the count shows per-field-group sums.

## Raw reference objects shown as cards (DONE display-side; root cause noted)

Diagnosed with the mythology snapshot: pool = `Character=500, Episode=15,
WikidataDynamicObject=1541`. The 1541 are unstamped references (mother/child/
father/episode targets) whose `typeName` has no generated class, so
`GeneratedQuizableMapper` keeps them raw (`forType(typeName)==null`). They were
leaking into the instances grid as out-of-order raw cards showing
`dynamicFields`/`type`/`source`. `QueryObjectResultPanel.groupByType` only
excluded them by the exact string `"WikidataDynamicObject"` — fragile (a ref
stamped with any other unrecognized type slipped through). Fixed: exclude **any
`q instanceof WikidataDynamicObject`** (raw = unmapped reference, never a domain
card) in `groupByType` and `searchPanelView` (latter falls back to raw only if
nothing typed, so it's never blank). Not caused by Source/B — those just made
the raw cards' content visible.

Root cause still open: references should be **stamped with their class** during
extraction (so mother/child map to typed `Character` and render as proper
chips/cards, not raw) — an extractor/`entityClassName` follow-up.

## Typed cross-references — compile domain together (DONE, #57)

The blocker for nested search/config: generated entity refs were `List<quiz.Quizable>`
(each class compiled standalone), so `QuizableFieldPaths` couldn't recurse into a
referenced class's fields, and cross-refs mapped to raw `WikidataDynamicObject`.
Option A, implemented:
- `RuntimeJavaCompiler.compileAll(qcn→source)` compiles several classes in one
  pass under one loader.
- `GeneratedQuizableRuntimeBuilder.build(project)` generates every class into ONE
  package and compiles them together → typed cross-refs resolve (Character ↔
  Episode).
- `GeneratedQuizableSourceGenerator.objectType` emits the referenced in-project
  class name (e.g. `List<Character>`), not `quiz.Quizable`, when the field's
  "Of class" matches a project class.
- `GeneratedQuizableMapper.mapObject(source, preferredType)` maps an ENTITY field
  by the field's `entityClassName` (falls back to the object's typeName), so an
  unstamped reference still maps to the typed class the field declares.
- `GenerateDomainQuery`: ONE whole-domain runtime + ONE shared mapper over all
  roots → each QID = one typed instance, cross-refs shared.
- `@Provenance` excluded from `QuizableFieldPaths` (no `…source.name` paths).

Verified on real mythology data: compiled together; mapped `{Character=500,
Episode=15}` with **zero raw** (was 1541 raw); fields `father/mother/child:
List<Character>`, `episodes: List<Episode>`; Episode field paths now include
`characters.name`, `characters.father.name`, `characters.episodes.name`, … So
nested search/sort/config work via plain reflection, and the raw-card + duplicate
problems are fixed at the root.

## Name-collision panel (DONE, #58)

Name collisions (names mapping to >1 entity, e.g. 5 Agenors) were query-log only.
Added a **"Name collisions (N)"** button in the generated-instances window
toolbar that opens a panel of `NameCollision` cards — each a `name` plus a
clickable `List<Quizable> entities`: the actual generated instance per colliding
QID (looked up from `run.instances()` by id), falling back to a `WikidataSource`
(QID + wiki link) for QIDs not materialized. The collision view **shares the
instances panel's render context** (`QueryObjectResultPanel.activeRenderContext()`
+ `setInPlaceNavigation`), so clicking a colliding entity **focuses/scrolls to
its card in the instances window**. `reportNameCollisions` builds the cards +
toggles the button (disabled when none). Collapsed-by-default, so the `(N)` count
is the headline; expand to click through.

## Unified cross-panel search (DONE, #59)

Per-class panels gave each section its own independent search box, so a match
belonging to another panel (an Episode's character) never surfaced from where you
typed. The match's true home is the entity's own card in its panel — so instead
of merging hits, share only the **input** and the **config**, and let each panel
search independently:
- `QuizableSearchPanel.setCoordinated(true)` hides its own input + config toolbar
  but keeps its **own per-field results panel + per-panel ◀/▶ navigation** and
  highlighting — the field path on each row identifies the owning panel.
- `MultiSearchBar` = one shared **input** + Highlight-fields + **Search/Sort/View
  Config** as single dialogs with **one tab per class** (classes as roots, one
  click to that class's fields). Typing fans the query to every engine
  (`runCoordinatedSearch`); Apply re-applies + re-runs for all.
- `MultiQuizableView`: shared bar on top; each section shows its own engine
  (results only).

So a character hit highlights on its Character card in the Character panel and an
episode hit on its Episode card in the Episode panel — one search box, one config,
per-panel per-field counts/navigation as before. The deep inline-reveal-through-
links problem is retired. Single-class instances keep their own box. (A few
coordination helpers from the abandoned merged-coordinator approach remain unused
and can be pruned.)

## Quantities: units once per field + @Numeric sort (DONE, #61)

Periodic-table refinements (general):
- **`quiz.Quantity`** {amount, unit} — renders "1538 °C", keeps the number.
  Codegen types `NUMBER` fields as `quiz.Quantity`; the mapper wraps values.
- **Unit once per field** (not per value — the per-value statement-node join
  timed out). `GeneratedFieldModel.unit`; `GenerationPipeline.resolveUnits`
  runs ONE aggregate query per Number field for the dominant English `P5061`
  symbol over the class's members, sets `field.unit()` (called from
  GenerateDomainQuery + fullRun). Verified: melting point → °C, density → g/cm³.
- **`@quiz.annotations.Numeric`** drives numeric sort, decoupled from the value
  type: codegen marks `NUMBER` fields; `QuizablePanelSearchAndSort.sortKey` uses
  the leaf field's annotation → sorts by the leading number ("1538 K" → 1538),
  no `Quantity`-specific code.
- Dates: `GeneratedQuizableMapper.formatWikidataDate` collapses the truthy ISO
  time to a year ("1875", "5000 BC"), dropping `T00:00:00Z`.

Caveat: units-per-field assumes a uniform unit per property (the user's call);
a value stored in a minority unit is labelled with the dominant one. Dynamic/web
path still unit-less (typed/desktop path carries units) — follow-up.

## Configurable membership relation (DONE, #62)

The membership editor (`ClassSourcePanel`) hardcoded the relation to P31
("instance of") and overwrote any other propertyPid on apply — so "Best Picture
films" (membership `P166 = Q102427`, "received the award") got clobbered to
`?value wdt:P31 wd:Q102427` ("instance of the award") → 0 objects. The model +
compiler already supported any relation; only the UI assumed one. Added a
**"Relation property:"** field (default P31; e.g. P166 award received, P39
position held); apply writes the configured PID. A concrete instance of the
"don't bend the load to an assumed structure" principle.

## ELT architecture: Load / Transform / ViewConfig

Three layers agreed: **Load** (acquisition — source-shaped classes, effective
queries), **Transform** (constructs that restructure loaded data into new view
classes; n-ary `{classes}→{classes}`, a DAG), **ViewConfig** (display config per
class; the `quiz.ui.viewconfig` package — `QuizablePanelConfig` + editor frame +
JSON IO). Principle: don't bend the load to the desired output; load expressively,
then **project** to view structures via constructs, then **present** via viewconfig.

### Transform — `invert` on the dynamic pool (DONE, #63)
`wikidata.explore.transform.{InvertConstruct, TransformEngine}`. Operates on the
snapshot `WikidataDynamicObject` pool: `invert(sourceType.refField →
targetType.backRefField)` stamps each referenced object with `targetType` and
adds a back-reference list. Materialized in the pool → `GeneratedSource.registerAll`
serves the new type automatically (searchable/sortable/view-configurable, no
plumbing). Verified: constellations → 9 `Hemisphere{constellations:[…]}` (Northern
23, Southern 45, …).

### ViewConfig reaches the web (DONE, #64)
`QuizableJson` now applies the per-type view config: loads
`data/viewconfig/<typeName>.json` (new `QuizablePanelConfigJsonIO.fileForType` +
`loadJson`), emits configured fields first in config order; the rest only when
`allFields`, else hidden. **Keyed by `typeName()`** so ONE config drives both the
desktop typed instances and the web's dynamic objects of that type. Verified:
config {area, abbreviation, hemisphere} → web emits exactly those, in order; no
config → all fields. Caveat: config cache is per-JVM (edit needs a server restart);
desktop editor should also key by typeName (it does for typed instances whose
simpleName == typeName).

### Transform — `reify` + config persistence + Run UI (DONE, #65)
A domain's Transform is now an editable, persisted artifact. `TransformConfig`
(`inverts` + `reifies`) saves as `<domain>.transform.json` alongside the model
(`TransformConfigStore`, Jackson; `isEmpty()` `@JsonIgnore`d, reads lenient so the
serialized getter never breaks a round-trip). New `ReifyConstruct` +
`TransformEngine.applyReify`: explode `sourceType.listField` into one `targetType`
object per (source, element) pair — `qid = src__el`, name `"src — el"`, holding
`sourceField=src` and `elementField=el` — added to the pool and returned;
`apply(pool, config)` runs inverts (in place) then reifies. A **"Transform…"**
button in the Generated-instances toolbar opens a dialog: edit the transform JSON,
**Run** against `lastRun.dynamicObjects()` (created objects shown in a
`QuizablePanelView`, per-type pool counts in the status line + log), **Save** next
to the model. Verified: invert → 9 Hemisphere (constellations); reify → 2
`Nomination{film, award}` from a `Film.awards` list.

### Load — non-lossy qualifier load → rich Nominations (DONE, #66)
The direct claim `?film wdt:P166 ?award` loses everything but the award. A new
**qualifier load** reads the statement path instead and keeps the qualifiers.
Built as an isolated Load enrichment (in the `DBpediaEnrichment` spirit — it
touches NONE of the WDQS-tuned `RuleNodeQueryBuilder` index/skip logic):
`QualifierLoader.enrich(pool, cfg, client, log)` runs one batched query per ≤200
entity QIDs — `?e p:P166 ?st . ?st ps:P166 ?value . OPTIONAL { ?st pq:P585 … }` +
`SERVICE wikibase:label` — and attaches one statement object (keyed by the
statement GUID, so unique/stable) per statement under `entity.statementField`,
holding the main value under `valueField` plus each qualifier (kinds `ENTITY` /
`YEAR` = `BIND(YEAR(?t))` / `STRING`). `QualifierLoadConfig` carries it;
`TransformConfig.qualifierLoads` persists it. `ReifyConstruct.promote` then
**lifts** those statements to top-level in place (keep their fields + add the
source back-ref) → a FLAT `Nomination{film, award, year}`, not a nested
`Nomination{film, award:{…}}`. `TransformEngine.apply(pool, cfg, client, log)`
orders it: qualifier-loads → inverts → reifies; the Transform dialog Run now
executes off the EDT (SwingWorker) and passes the client. **Verified live**: 5
Best-Picture films → 61 `AwardStatement`s carrying P585 ceremony years →
reify-promote → 61 `Nomination{film, award, year}`. Persistence + pre-promote
back-compat round-trip clean.

### Oscars as award-EVENT root (the OscarNomination shape, complete)
The hand-built `oscar.OscarNomination{nominee, award, work, ceremonyYear, winner}`
loaded by an ad-hoc per-award Wikidata pager misses data. The ELT path reproduces
it completely: each P166 statement → an `Oscar{nominee, award, ceremonyYear,
work}` event. Wikidata shape (probed live): "Academy Awards" is **Q19020**; every
category (Best Picture Q102427, Best Actor Q103916, …) is `P31 Q19020` — **56**
categories. `?winner wdt:P166 ?cat . ?cat wdt:P31 wd:Q19020` = **4057** distinct
winners (2823 humans, 935 films, + short/animated films, songs, companies) across
**5724** award statements. Added `QualifierLoadConfig.valueTypeQid` so the load
keeps only statements whose value is `wdt:P31` the type (Q19020) — dropping every
non-Oscar award a winner also holds. Verified live: Daniel Day-Lewis → exactly his
3 Oscars `{Best Actor 2004 Mystic River, Best Actor 2009 Milk, Best Supporting
2026 …}`, no Golden Globes. **Remaining piece**: the winner-set root membership
("received an instance of Q19020") — a 2-hop / intermediate-type membership (or
the 56 category QIDs as a multi-QID set) so the root yields all 4057 winners;
then qualifierLoad(value-type Q19020) + reify(promote) materializes all 5724
events. Next also: surface qualifier-loads as guided Transform-dialog rows, and a
membership/coverage advisor.

## Grouped root query: row-LIMIT cross-product (DONE, #67)
`Generate domain` on Oscars returned **4** films, not ~98. Membership was correct
(`?value wdt:P166 wd:Q102427` + `NOT EXISTS Q5` = 98); the bug was the grouped
(GROUP_CONCAT-inlined) root values query putting every single-valued scalar field
in `GROUP BY`. Multi-valued properties modeled SINGLE (P577 release dates, P495
countries, P364 languages, P750 distributors) cross-product the rows — 98 films →
**2059 rows** — and the inner `LIMIT` counts ROWS, so ~120 rows ≈ 4 films. Fix
(`RuleNodeQueryBuilder`): in the grouped path, `SAMPLE` the scalar included fields
(`?<var>_s` → `AS ?<var>`, so the SELECT name + extractor index are unchanged),
leaving non-inlined COLLECTION fields plain; `GROUP BY` then reduces to `?value
?valueLabel` and the LIMIT counts distinct entities — the same fix
`childQueryForParent` already applies to child edges (R11/R13). Verified: the
generated query now returns all **98** distinct films. Single-valued domains
(constellations) are unchanged (SAMPLE of one = same value).

## No-membership class scanned all of Wikidata (DONE, #68)
A class with no membership relation AND no seed QIDs produced a query whose only
content was the comment `# no membership and no seed QIDs — empty result` — but no
actual constraint, so the downstream `?value rdfs:label ?valueLabel` matched EVERY
labelled entity and generation returned ~5000 arbitrary items (Q27168368 …) plus a
full-database scan. Fix (`RuleNodeQueryBuilder`): in that branch bind `VALUES
?value { }` (empty set → 0 rows, cheap) and skip the label pattern. Verified empty
VALUES is valid SPARQL (WDQS 200, 0 rows). Such a class is typically meant to be
materialised by a Transform (an Oscar/Nomination event has no Wikidata membership
and shouldn't be generated directly) — a follow-up could warn "class X has no
membership" in the generation log rather than silently returning empty.

## General relation explorer (DONE, #70, #71)
Two UI gaps hit while building the Oscar-nominations class by hand:
- **Property search** (#70): the relation-property field was PID-only — no way to
  find P1411 by name (the one "Search" is item-only, `type=item`). Added
  `WikidataApiClient.searchEntities(q, limit, type)` (supports `type=property`), a
  **"Find…"** button → modal property search ("nominate" → P1411 "nominated for"),
  the resolved relation label shown next to the PID, and BOTH the type and
  relation labels are now clickable **links** to their Wikidata page. Also: the
  source-QID row label adapts — "Wikidata type/class:" only for P31, else
  "Relation target (PID):" (#69), since with P166/P1411 the field holds the
  relation's target (an award), not a type.
- **"Explore relations"** (#71) was a fixed 5-probe Greek-myth battery
  (has-part / part-of / member-of / participant) that returned nothing for awards
  or any non-myth entity. Generalized (`ExploreEntityQuery`) to list ALL outgoing
  + incoming direct-claim relations with a count + sample example. Perf: a UNION
  of two aggregate subqueries + outer label joins, or a per-member label join,
  times out (~65s) on a popular entity — so it runs TWO queries (out, in), keeps
  the property label INSIDE each grouped subquery, and resolves only one SAMPLEd
  example per relation in the outer OPTIONAL. Full member lists for "Add relation's
  members as Seed QIDs" are fetched on demand by new `RelationMembersQuery` with
  the predicate BOUND (fast). Verified: Best Picture Q102427 → P1411 nominated for
  (1223), P166 award received (228), P1346 winner (98) in <2s.

## Oscars nominations — configuration walkthrough (learnings, in progress)
Building the nominations class by hand in the UI, logging what the workflow
teaches us (drives the navigation/skeleton roadmap — see memory
`wikidata_schema_skeleton_direction`).

- **Relation vs target naming**: the membership is `?value <relation> wd:<target>`.
  Default relation P31 makes the target a *type*, so the field reads "Wikidata
  type/class:"; with P1411 (nominated for) the target is the *award*, so the field
  relabels to "Relation target (P1411):" (#69). The "Find…" property search (#70)
  finds P1411 by name; seeding it with the PID gives an exact match.
- **No-target trap**: a relation set with a blank target produces 0 (empty
  membership) — now caught pre-flight with a specific dialog (#72) instead of a
  silent empty panel.
- **Finding the target set**: the categories are `Q19020 wdt:P527 ?cat` (35
  current) or `?cat wdt:P31 wd:Q19020` (59 all-time). Enumerating them by hand
  into "Also include types" works today; the cleaner path (membership runs that
  sub-query = 2-hop) is deferred. Explore-relations on Q19020 surfaces them.
- **Discovery is term-based**: the user searches a sensible label ("academy
  awards") → Explore finds Q19020; resolving alternative wordings ("oscars") is a
  future NLP layer, not part of the UI.
- **Testing a relation's output**: Explore's table already previews each relation
  (count + one example); a new **"Show members"** button (#73) lists ALL members
  read-only in a dialog, so the user can test what P31/P166/P361/P527 produce on
  Q19020 without committing to seeds. (First concrete navigation/preview aid
  toward the schema-skeleton.)
- **Count is DISTINCT, and the path matters**: Explore's count is
  `COUNT(DISTINCT member)`; a raw query with an `OPTIONAL P31` join inflates rows
  (each category repeats per class it instances). For Q19020 the category set
  differs by path: `has part → (P527)` = **18** (curated/current) vs
  `instance of ← (P31)` = **59** (all-time, incl. discontinued). The fuller
  nominations target set is the P31-incoming 59, not P527's 18 — a real modeling
  choice the Explore counts make visible. (Learning: surface BOTH the relation and
  the distinct count so the user picks the right hop.)
- **Explorer became a graph browser** (#74): from Show members, double-click (or
  "Explore selected ▶") a member to make it the next explored entity; a header
  shows "Relations of: <label> (<qid>)" and "◀ Back" retraces a history stack. So
  the user walks the graph live — Academy Awards → has part → Best Actor → its
  relations → … — instead of one-hop. This is the navigation primitive the user
  asked for ("do the configuration live") and aid #1 toward the schema-skeleton.
- **Model = graph, now rendered** (#75): the user observed that navigating/
  configuring is really building the rule-tree (RuleNode: class = node, reference
  field = edge to a child class). New `RuleTreeGraphPanel` ("Model graph" button)
  renders `GeneratedProjectModel` as class boxes + labelled field-edge arrows
  (collections `[*]`, scalar fields listed in the node). It's a **map + selector,
  not an editor**: click a node → select that class in the workbench; the
  workbench selection mirrors back as the highlighted node (two-way "connection"
  without embedding the panels). Verified: mythology Character/Episode + 5 edges
  (incl. self-refs); constellations Constellation→Star. Next toward the
  skeleton: overlay the explored Wikidata neighbourhood + highlight committed
  edges; improve self-loop rendering.
- **Graph ↔ Explorer bridge** (#76): in the Model graph, single-click a node =
  select its class; **double-click = open the Explorer on that class's membership
  target** and show its Wikidata relations live. First step toward "walking the
  graph and drawing the model are the same canvas" — from the model you jump into
  navigation, and use-as-type/add-members feeds discoveries back. (Continuation:
  overlay the explored neighbourhood + highlight committed edges in one canvas.)
- **Unified QID/PID link rendering** (#77): link logic was duplicated 3× and
  missing in spots (subtypes dialog, Explore Show-members showed plain text). New
  `WikidataLinks` (isId/url/open for Q and P, `linkify(JLabel,Supplier)`,
  `installOnColumn(JTable,col)`, via `aux.BrowserLauncher`) now drives every QID/
  PID across the result tables, class-panel labels/search/subtypes, and Explore
  member lists. Follow-up the user flagged: one renderer for whole qid+label+
  count+examples rows (the tables still differ in shape) — link part now shared.
- **Config-friction fixes** (#78): (a) limit spinner max was 10000 so a larger
  value reverted to the saved limit on Apply (looked like "jumps back to 5000") —
  raised to 1M + `commitEdit()` on apply. (b) Populating "Also include types"
  required seed-and-cut/paste — added an **"Add as relation targets"** button in
  Explore that appends a relation's members to the active class's
  `additionalTypeQids`. Clarified: leave the main "Relation target" BLANK and put
  all targets in "Also include types"; the compiler unions sourceQid +
  additionalTypeQids, so one-in-the-main-field is unnecessary.
- **Open gap noticed**: the Model graph shows class nodes + scalar fields +
  reference edges but NOT the membership (relation + target), so for a class like
  Oscarnominations (P1411 → categories) the node doesn't reflect what you're
  configuring — the graph↔config connection isn't visible. Next: render membership
  on the node (relation + target count), and for a 1-class model the click→select
  is invisibly trivial.
- **Nominee load works**: relation P1411 + all 59 `instance of Q19020` categories
  in "Also include types" (via Add-as-relation-targets) → **11,075 instances**
  (≈ the measured ~11,154 nominees; difference = requireLabel + dedup), **103 name
  collisions** (expected among ~11k nominees — many shared names). The membership
  half of the nominations domain is done. Next: Transform — qualifier-load P1411
  (value = category, qualifiers P585 year / P1686 for-work) + reify(promote) →
  ~23k `Oscar{nominee, category, year}` event instances.

## WikidataDynamicObject deserialization (#80)
Loading the OscarNomination cache threw `UnrecognizedPropertyException: "url"`.
`getUrl()` is a derived public getter → Jackson writes `"url"` (and identifier/
displayName) but can't read them back, and the class wasn't lenient. Fix:
`@JsonIgnoreProperties(ignoreUnknown = true)` — only name/qid round-trip.

## Large-result rendering — bucket experiment REVERTED (#79)
**Reverted.** The lazy alphabetical-bucket `QuizableGroupView` for a single large
class was a UX catastrophe (tree + separate windows), while the multi-class path
(MultiQuizableView, e.g. constellations) rendered fine. Clarified with the user:
the Oscars cards render the SAME as constellation cards (proper QuizablePanel) —
the issue is **purely speed at ~11k materialised cards**, not the renderer. So the
real fix is performance done right (data-driven search + a non-disruptive lazy/
virtualized approach), NOT imposing a different layout. Original flat rendering
restored. (Earlier #79 notes below describe the reverted approach.)

### (reverted) lazy bucketed group view notes
11k single-type instances rendered flat (one materialised card each) → slow layout
+ scroll. Diagnosis (with the user, who built the renderer): the per-card paint is
already optimized — `QuizablePanel` draws fields via painted `QuizableTextBlock`
rows, the benchmark win that took it ~20s→<2s vs Swing labels. So the remaining
cost is laying out/scroll-painting ~11k components AT ONCE, not the per-card tech.
Fix
(step 1): single type > 800 → lazy `QuizableGroupView` (reused, like hand-built
`OscarNominations`), bucketed alphabetically; only the OPEN bucket's cards build
(`computeIfAbsent`). Caveats: buckets open in a separate window; no shared render
context across buckets; no search yet. Step 2 (next): **data-driven search** over
each instance's `Row(fieldPath, value)` — count + highlight without materialising
cards (the component-driven `QuizableSearchPanel`/`MultiSearchBar` need rendered
cards, which laziness avoids). `MultiQuizableView` builds all sections eagerly so
it can't carry the lazy case.

## Discover/Sample empty for multi-target membership (#81)
Discover properties found nothing for Oscarnominations. `RuleNode.sampleCopy()`
copied sourceQid/propertyPid/direction but NOT `additionalSourceQids` — so for the
P1411→59-categories membership (all targets in the additional set, blank sourceQid)
the sample copy had no membership and emitted the empty `VALUES ?value { }` query
(#68) → 0 rows. Fix: sampleCopy now also copies additionalSourceQids + membership
filter (membershipPid/Qid) + requireSitelink. Verified the sample returns 10
nominees. (Separate lesser issue: Discover's "Override QID" hardcodes `?item
wdt:P31 wd:<qid>`, so a relation-target category QID yields nothing — use the
selected class, not the override.) This unblocks the field-discovery vision:
Discover on the selected class samples nominees + lists their properties with
`count/sampleSize` coverage — and partial coverage (e.g. director 41/100 vs
date-of-birth 59/100) is the Film-vs-Person subclass signal the user described.

## Discover: properties by target → subclass structure (#82, #83)
Toward "see properties per target qid, grouped" (the field-discovery vision).
- **Stratified sample** (#82): a flat LIMIT could draw every sample row from one
  target; `RuleNodeQueryBuilder.stratifiedSampleQuery` (`GROUP BY ?target /
  SAMPLE`) takes one per target. Used by Discover for ≥2 targets → 49 Oscar
  categories represented.
- **Properties by target** (#83): `DiscoverPropertiesByTargetQuery` + "By target…"
  button. Two cheap bounded steps — (1) one (target,instance) pair per target;
  (2) profile those pairs grouped by target+property, dropping external-id props.
  Tree view: each category → its properties. Reveals the latent subclass split —
  person-categories (Best Actor → date of birth, spouse, occupation…) vs
  film-categories (Best Animated → genre, award received…). Verified ~4.7s, clean.
  Next: auto-cluster targets by profile similarity into explicit Person/Film
  subclasses (the user's "grouped" endgame).
- **k-per-target + property→targets** (toward auto-clustering): the "N:" spinner
  now drives the per-target sample size (GROUP_CONCAT instances per target,
  keep first k client-side), so the leaf count becomes real within-category
  coverage (0..k) instead of always 1. Each property leaf carries its pid/label;
  clicking it lists every target that has it (the inverse index) in a split-pane
  detail — so `date of birth` → all the person-categories, the first concrete
  subclass cluster. CAVEAT: profile cost ≈ N × #targets instances; for a
  many-target class (Oscars ~49) a large N is slow (k=5 → ~245 instances, heavy).
  Keep N small (1–2) for many-target classes, or optimise the profile later.

## Open questions

- How large a Character set to generate (5,250 full vs Notable-only vs limit)?
- Model genealogy as edges to one `Character` class (self-ref) vs separate
  sub-classes — start self-ref.

## Auto subclass detection: cluster targets by profile (step 2)
The "By target" view already gives each membership target's property profile +
the property→targets inverse index (step 1). Step 2 clusters those targets by
profile similarity into candidate `extends` subclasses.
- **`wikidata.explore.analysis.SubclassClustering`** (pure, unit-tested): Jaccard
  similarity over each target's pid-set, agglomerative **average-linkage** — start
  one cluster per target, merge the most-similar pair while avg similarity ≥ a
  threshold. Average linkage (vs single) resists chaining; O(n³) is fine at ~50
  targets. Each cluster reports a **signature** = properties ≥ minCoverage (0.5)
  of members carry, best-covered first. `clusterRows(...)` adapts the flat
  `[targetLabel, propLabel, pid, count]` rows the by-target query emits.
  Tests (4, green): Person/Film split, signature = shared props, unrelated target
  stays singleton, Jaccard basics.
- **UI**: `PropertyDiscoveryPanel.acceptByTarget` dialog gains a control strip —
  a similarity slider (0.10–0.90, default 0.40) + "Suggest subclasses". Click →
  modeless tree: each "Subclass N (k targets · top props)" with `shared properties`
  (pid + members/size coverage) and `members` sub-nodes.
- NOT yet: materialise a cluster into the model as a real `extends` subclass —
  the by-target query returns target LABELS, so wiring clusters → "Also include
  types" QIDs needs the query to also surface ?target qid. Next step.

## Fix: stratified-sample timeout + "Failed" with no detail
User hit Discover→Run on Oscarnominations (multi-target P1411 → 58 categories):
the "Sample class QIDs" stratified query failed after 33s showing only "Failed".
- **Root cause (perf):** P1411 "nominated for" is a GENERIC predicate (Grammy/
  Emmy/Nobel/… across all of Wikidata). The planner could scan every P1411
  statement before filtering to these 58 targets → intermittent timeout (the same
  query ran in 3.6s when WDQS was unloaded). Fix: `hint:Query hint:optimizer
  "None"` at the top of the inner SELECT forces VALUES ?target to bind FIRST, so
  the predicate is looked up per bound target (POS index). Verified live: 3.6s →
  1.1s cold (0.22s warm), 58 sampled. Applied to BOTH
  `RuleNodeQueryBuilder.stratifiedSampleQuery` and step-1 of
  `DiscoverPropertiesByTargetQuery` (same VALUES+generic-predicate shape).
- **Root cause (reporting):** a dropped-connection / timeout exception can have a
  null message, so `LogNode.complete` fell back to the status default "Failed".
  `WorkflowRecorder.describeError(Throwable)` now walks the cause chain and uses
  the simple class name when a message is blank → the failed step always says why
  (e.g. "SPARQL HTTP 500…", "HttpTimeoutException"). 4 tests.

## Inline link helper + "By type" recipient-P31 discovery
**WikidataLinks reusable inline linking** (the "wrap text in one call" ask): a
label always travels WITH its qid/pid in our data, so we link the (id,label) PAIR
directly — no fuzzy phrase matching. Added: `Linked(id,label)` record;
`linkHtml(id,label)` → anchor showing label, linking to the wiki page (escaped
fallback when not linkable); `html(text)` → links bare PID/QID tokens in free
text; `pane(htmlFragment)`/`setHtml` → a label-styled JEditorPane that opens links
via BrowserLauncher. Wired into the by-target detail (property + each target
category now clickable). The by-target query now also returns `?target` qid
(TargetQid appended last, leaves clustering indices stable). 5 tests.

**"By type…" — recipient P31 per target** (`DiscoverRecipientTypeQuery` + button):
same bounded 2-step sampling as by-target, then `?inst wdt:P31 ?type` grouped by
target. Intended to key subclasses by the discovered recipient type (human/film).
**FINDING (verified live): the dominant-P31 split does NOT work** for Oscars —
P1411 links BOTH the person and the work to a category, so the type set is mixed:
Best Actor = film 435 / human 251, Best Picture = human 619 / film 601, Best
Director = film 443 / human 254. A winner-take-all pick would mislabel person
categories as film. So the view shows the FULL per-target type distribution + a
type→targets inverse index (honest), NOT an auto subclass. A clean person-vs-work
split needs the nomination reified (Oscar{nominee, award, work} — the existing
qualifier-load/reify Transform), where the type is per-ROLE, not per-category.

## Capture category + type per nominee (data-driven subclassing)
User's insight: don't re-discover per category — when generating the 11K nominees,
just KEEP the category and the type on each nominee as fields, then grouping falls
out of the data. Added two ENTITY fields to Oscarnominations (root + its classes[]
copy, ROOT_TO_ITEM, COLLECTION):
- `category` (P1411) — allowedQids restricted to the 59 Oscar categories (P1411 is
  generic "nominated for", so the restriction drops non-Oscar nominations).
  appendAllowedQids → includedQids VALUES-filters ?value, verified in builder.
- `type` (P31) — the nominee's own instance-of (UNRESTRICTED).
Why this beats the earlier approaches: P31 is mixed PER CATEGORY but unambiguous
PER INSTANCE, so grouping the generated pool by `type` gives the clean Person
(Q5) / Film (Q11424) split with zero extra SPARQL; grouping by `category` gives
per-category samples for free (subclasses OR QuizableGroups, decided from the
observed distribution). Snapshot is now stale vs model → user must Generate to
populate; model-signature guard will warn (expected). Backup at
/tmp/oscarnominations.model.json.bak.

## Also wired (this session)
- "From parts…" button in ClassSourcePanel: discover membership targets from a
  parent's P527 (e.g. Q19020 → its award categories) via the existing
  RelationMembersQuery → Add selected / Add all to "Also include types". Makes the
  category membership data-driven instead of a pasted 59-QID list.

## Generalized: intrinsic grouping fields + named membership patterns
The "category + type per nominee" idea is general — any class gathered by a
multi-target/-type membership should carry the grouping dimensions as fields.
- `MembershipFields.ensure(clazz)` (model pkg): for a class whose membership
  spans >1 type, add a `type` field (P31); for the RELATIONAL case (non-P31
  relation → target set) also add a `target` field (the relation, allowedQids =
  the target set). Deduped by property+direction (won't double Oscars' hand-made
  `category`/`type`). User chose "auto-inject, visible/editable" → wired into
  `ClassSourcePanel.apply()` so the fields appear as real editable model fields
  when membership is configured (logged).
  - Star case (user): collecting Star by P31 ∈ {star, red giant, variable star…}
    → gets `type` only (target == type), keeping the subtype per star for
    subclassing/grouping. Single exact P31=Qx → nothing (constant type).
- `MembershipPattern` enum (named patterns: Single type / Type + subtypes /
  Single- & Multi-target relation / Seeded / Unconfigured) + `describe()` e.g.
  "Multi-target relation (P1411 → 59)". Shown on the class tree node via
  `ClassPatternTreeRenderer` (display-only; toString untouched so expansion keys
  still work), with the `extends` base: `Nominee : Person   [Type + subtypes]`.
- Tests: MembershipFieldsTest (4) + MembershipPatternTest (6). Suite 31 green.

## Generic qualifier discovery (de-Oscar-ifying the structuring)
'21 Grams' tangle diagnosed: a nomination is (person, category, work, year),
denormalized onto BOTH endpoints via complementary qualifiers — person side has
P1686 "for work" + P585 year; work side has P2453 "nominee" + P585. Fully
recoverable from qualifiers.
- `oscarnominations.transform.json` authored (qualifier-load P1411, valueType
  Q19020, forWork P1686 + year P585) — the verified generic engine, Oscar config.
  Parse-guarded by OscarTransformConfigTest.
- **Made generic** (user: "as generic as possible"):
  - `DiscoverQualifiersQuery` — for any ITEM_TO_ROOT relational membership: sample
    members, read the relation's STATEMENTS' qualifiers + datatype + coverage,
    restricted to the target set (drops non-membership statements). hint:optimizer
    "None". Verified live on P1411: found for-work(P1686), year(P585),
    nominee(P2453), together-with(P1706) on its own — no hardcoding.
  - `kindFor(propertyTypeUri)`: WikibaseItem→ENTITY, Time→YEAR, else STRING.
  - `QualifierLoadConfigs.fromQualifiers(...)`: builds a QualifierLoadConfig from
    discovered qualifiers; `fieldName` camel-cases the label ("for work"→forWork);
    `withoutNoise` drops P805 bookkeeping.
  - UI: "Qualifiers…" button in PropertyDiscoveryPanel → dialog listing the
    qualifiers (linked) + a GENERATED qualifier-load config JSON to paste into the
    Transform dialog. Same flow for any domain.
- Tests: QualifierLoadConfigsTest (4) + OscarTransformConfigTest (1). Suite 36 green.

## Declarative facets: grouping becomes part of the domain
Resolved the subclass/group border: a discovered dimension is a FIELD SOURCE
(value per entity); SUBCLASS (model, extends) = schema partition (fields diverge);
FACET/GROUP (view) = same-fields partition by a value. "A subclass is a facet that
also changes the schema." Grouping infra existed (FacetGrouper builds
dimension→bucket trees) but facet declarations were hand-built per dataset — so
grouping wasn't part of the domain. Now it is:
- `GeneratedFacet` (model) {name, fieldName, bucketing: VALUE/FIRST_LETTER/RANGE,
  rangeSize} + `GeneratedClassModel.facets()`. Saved with the class (Jackson).
- `wikidata.explore.view.DomainFacets`: `toFacet(spec, class)` → runtime
  `quiz.facet.Facet` (reference() for entity fields, mapped() for first-letter /
  range; no FacetGrouper change needed); `suggestFor(class)` proposes by-type
  (P31), by-membership-target, by-decade (date/year). Pure bucketing fns
  (firstLetter, rangeBucket → "2000s") unit-tested.
- UI: facets show as ⊞ child nodes under the class (distinct colour) in
  SingleRootClassModelPanel; "Suggest facets" button adds proposals; Remove
  deletes a facet. Oscars model seeded with by-category + by-type.
- Layers now clean (ELT): Load=field sources · Model=subclasses · View=facets.
- Tests: DomainFacetsTest (4). Suite 40 green.
- NOT yet: wire DomainFacets.toFacets() into the live quiz/web presentation (the
  declared facets aren't consumed by the running views yet) — next step.

## Full canonical reify (the 21 Grams fix) + the discovery aid
'21 Grams' is a FILM that "covers" 2 human nominations because P1411 is stored on
both endpoints with complementary qualifiers — film side has nominee (P2453) + no
forWork (it IS the work); person side has forWork (P1686) + no nominee. Verified.
- `ReifyConstruct` gains `roles` (a field = qualifier value ∨ source entity) +
  `dedupBy`; back-compat ctors + null-normalizing compact ctor.
- `TransformEngine.applyReify` promote path applies roles (nominee=nomineeQual ∨
  subject, work=forWorkQual ∨ subject) then dedups by key → film-side and
  person-side collapse to ONE Oscar{nominee, award, work, year}. In-memory test
  (OscarReifyTest) proves the collapse + that Best Picture keeps the film as
  nominee.
- `oscarnominations.transform.json`: adds nominee (P2453) + the canonical reify
  (roles nominee/work, dedupBy [nominee,category,work,year]).
- THE AID (generic, not Oscar-specific): `QualifierLoadConfigs.suggestTransform`
  proposes the WHOLE transform (load + de-denormalizing reify) from discovered
  qualifiers — each ENTITY qual → a role (qual ∨ subject), dedup over value +
  entity/year fields. The "Qualifiers…" dialog now shows this full proposal.
- Tests: OscarReifyTest (2), suggestTransform (1), updated OscarTransformConfigTest.
  Suite 44 green.

## Design thread: model-building as an explicit decision graph (the teaching script)
User insight: we keep making CONTIGUOUS structural decisions (membership kind →
target set → type uniform/mixed → qualifiers → denormalized? → facets), each
unlocked by the last. The missing abstraction = the decision itself as a node:
{question, appliesWhen, info-tool, options, hint, resolvedAs}. Equivalent to the
"how to use the model-builder" teaching script. The model graph should render this
prospectively (open branches + tool + hint) and retrospectively (resolved steps) —
extending the parked provenance idea. Proposed first slice: a DecisionCatalog (the
~9-step Oscar script) + a per-class "next steps" guide panel reusing the existing
probes. NOT yet built — awaiting go-ahead.

## Structural-decision abstraction: the model-builder explains itself (first slice)
Built the "teaching script" as data — the guided workflow surfaced per class.
- `wikidata.explore.advisor`: `DecisionContext` (pure model+transform state, no
  network: pattern/relational/targetCount/hasTypeField/hasSubclass/hasFacetOnField/
  qualifiersLoaded/reified), `StructuralDecision` (record: question, tool, hint,
  applies, resolved), `DecisionCatalog` (the ~7 ordered decisions = membership →
  targets → intrinsic-fields → type-structure → qualifiers → denormalization →
  facets; `evaluate`/`next`/`openCount`). Each decision carries the triple the
  user asked for: what to know · the tool that answers it · the hinted decision.
  Resolution is read off model state (subclass/facet present, qualifier-load/reify
  in the saved Transform). 5 tests walk Oscar through the script.
- `ModelingGuidePanel`: HTML view of evaluate() — resolved steps ✓, open branches
  with �', tool, hint; the next step bolded. Refresh re-evaluates.
- Wired: "Guide…" button in ModelBuilderFrame config header → window over the
  active class (context = project + activeClass + loaded transform.json).
- Advisory (read-only) first slice; rendering onto the model graph
  (RuleTreeGraphPanel) is the next step. Suite 49 green.

## Subclass discriminator: extends membership INTERSECT a P31 type
The gap: `extends` is inherit-OR-replace membership; a subclass couldn't express
"nominee AND human" (the intersection). Fixed with a discriminator:
- `GeneratedClassModel.discriminatorTypeQid` (+ hasDiscriminator, copy, also fixed
  copy() to carry facets). A subclass leaves its own membership blank (so it
  inherits the base's relational membership) and sets this P31 type.
- `RuleTreeCompiler.compileClass`: when `clazz.hasDiscriminator()`, set the node's
  membership FILTER (membershipPid=P31 / membershipQid=disc) ON TOP of the
  inherited membership → `?value wdt:P1411 ?cat . VALUES ?cat {…} . ?value wdt:P31
  wd:Q5`. Reuses the existing appendMembershipFilter (applied in the main
  generation paths 231/390/487).
- UI: "Subtype (P31):" field in ClassSourcePanel (load/apply, Wikidata-linked
  label), next to "Extends:". So a subclass is configured on the UI: Add class →
  name → Extends=base → Subtype=Qx → Apply.
- Tests: SubclassDiscriminatorTest (3) — intersection in the node + SPARQL.
  Also: QuizableFieldPaths now exposes name+qid for entity objects in
  search/sort/viewconfig (2 tests). Suite 52 green.
- NOT yet: the "Create subclass from type" bulk action (turn the generated Type
  values into subclasses in one click) — convenience on top; manual path works now.

## Web: group classes by domain
Flat class list doesn't scale across domains. The domain→types mapping already
lives in datasets.json (each Dataset has name + types).
- Server: `QuizableHttpServer.domains(LinkedHashMap<name,types>)` + `/api/domains`
  → `[{name, types:[…]}]`. Built in QuizableServerMain from the registry (each
  dataset's served types) + an "Other" bucket for the hand-built sources
  (SportTeam/State/Mythology/Oscar) not claimed by a dataset.
- Client: `api.getDomains()` (falls back to a single "All" group on an old
  server), and `+page.svelte` renders the type tabs grouped per domain (uppercase
  domain label, divider between groups). Java tests green, web build clean.
- Follow-up if wanted: same grouping on the quiz/pairing pages' type selectors.

## Child-object edges to relational (type-less) classes + restored Production dropdown
Adding Category.nominees (incoming P1411, Of class = the relational root
Oscarnominations) exposed two real generation bugs + a UX regression:
- BUG 1 (wrong type): RuleTreeCompiler set `child.sourceQid(parent.sourceQid())`,
  stamping the parent Category's Q19020 onto the nominee child → query demanded
  `?value wdt:P31 wd:Q19020` ("nominees that are categories") → 0. Masked for
  `stars` only because Star has its own type (Q523) applied as a membership filter.
  Fix: child takes its type from the REFERENCED class (the filter), not the parent.
- BUG 2 (skipped child): with the type removed, the per-parent query saw blank
  membership and short-circuited to `VALUES ?value { }` (empty), ignoring that the
  PARENT EDGE binds it. Fix: `parentAnchored` (rootQidOrVar is a Qid) → not the
  empty case → emits `?value wdt:P1411 wd:<category>`. Child edges now work for any
  referenced class, not just type-membership ones.
- UX: the "Load as" Production dropdown was orphaned (declared, not in the form) and
  `autoProduction` force-set every entity collection to inlined. Restored the
  dropdown ("Load as:" row); apply() now reads it (user's choice authoritative),
  autoProduction only seeds a default into it. So a large collection can be set to
  "Related objects" (batched) explicitly. Suite green.

## Invert as a configurable field production (derived, not fetched)
Category.nominees is the INVERSE of Oscarnominations.categories — it can't be
fetched (cyclic child edge, grandchild "simple" edge unreachable by depth). Made
inversion a first-class FIELD production instead of a separate Transform:
- `FieldProductionKind.INVERT` ("Invert (reverse of another field)") — selectable
  in the restored "Load as" dropdown.
- `RuleTreeCompiler` skips INVERT fields (not in the query plan).
- `transform.ModelInverts.derive(project)` turns each INVERT field into an
  InvertConstruct: sourceType = the field's Of-class, targetType = owning class,
  backRefField = field name, refField = the forward field on the source class that
  references the owning class via the same property (e.g. Oscarnominations.categories).
- `GenerationPipeline.fullRun` applies the derived inverts after extraction (before
  snapshot) via TransformEngine.applyInvert — in memory, no query, no depth, no cycle.
- Tests: ModelInvertsTest (derive + end-to-end fill). Suite green.
- Requires the INVERT field's Of-class to name the class holding the forward field.

## Qualifier as a field-source (statement reification configured on the model)
The "Category → years → nominees" structure is the reified Nomination (year is a
per-(nominee,category) edge fact = the P585 qualifier). Made it field-configurable,
not a Transform file:
- Model: `FieldSourceMapping.qualifierPid` (a field draws from this pq: qualifier)
  + `GeneratedClassModel.statementSourceClass` (instances = the statements of this
  class's relation property on each member of the named class).
- `transform.ModelStatementReifications.derive(project)`: a statement-reification
  class → QualifierLoadConfig (entityType=source class, propertyPid=relation,
  valueField=the non-qualifier field matching the relation pid, qualifiers=the
  qualifier fields with kind from field type: DATE→YEAR, ENTITY→ENTITY, else
  STRING) + ReifyConstruct (promote, ENTITY qualifiers become subject-fallback
  roles, dedup over value+quals). `.apply(...)` runs qualifier-load (network) +
  reify, returns the created records.
- Wired into GenerateDomainQuery after extraction (qualifier-load needs the
  client); reification classes are skipped from normal root generation; created
  records added to allRoots (materialised) + the snapshot pool.
- UI: "Reifies statements of:" (class panel) + "Qualifier of (PID):" (field panel).
- Tests: ModelStatementReificationsTest (2). Suite green.
- This is the "qualifier" field-source kind (direct / invert / qualifier / derived).
  Recipe: Nomination reifies-statements-of Oscarnominations, relation P1411, type
  Q19020; fields category(P1411 value), year(Qualifier P585, Date), forWork(P1686),
  nominee(P2453); then Category→years→nominees is a facet view over Nomination.

## Class-name refs case-insensitive + qualifier-load shows in Query Logs
- Renaming a class (Oscarnominations → OscarNominations) silently broke every
  reference (a field's "Of class", a "Reifies statements of") because findClass was
  case-sensitive → the invert/reify couldn't resolve the source class (Category
  empty, no Nominations). Fix: `findClass` exact-first then case-insensitive
  fallback; ModelInverts/ModelStatementReifications use the RESOLVED class's real
  name in the derived configs so the pool's stamped typeName matches too.
- Qualifier-load ran queries via the client (stdout) without `log.subquery()`, so
  the Query Logs panel never showed them. Now each batch logs a structured
  subquery (clickable SPARQL) titled with m/n progress
  ("Qualifier load 3/240 (P1411, 50 entities)"); split/failed batches log
  subqueryFailed with a/b sub-labels. Suite green.

## Qualifier-load: anchor on the value set (general) + cancellable
- Slowness was the qualifier-load anchoring on the ENTITY set (`VALUES ?e {nominees}`,
  ~240 batches re-walking the same P1411 edges). GENERAL fix: when the config has a
  valueTypeQid, anchor on the VALUE set instead — fetch its instances
  (`?value wdt:P31 wd:<vt>`), batch by them (VALUE_BATCH=8), `VALUES ?value {…}`;
  rows still carry ?e so they attach to pooled entities. Falls back to
  entity-batching with no value-type. Oscars: ~58 categories vs ~12k nominees →
  ~8 queries instead of 240. Not domain-specific.
- Cancel: the load loop now checks Thread.interrupted() per batch and bails (and
  loadWithSplit stops on interrupt instead of split-retrying), so Cancel stops the
  series. Suite green.

## Effective nominee was already done; wired DECLARED facets into the web
- The "derived effectiveNominee" turned out to need no new field: the reify already
  resolves nominee = COALESCE(P2453 qualifier, source) via its subject-fallback
  Role, proven by OscarReifyTest (nominee=person on the film-side statement, =film
  for Best Picture). So nominee is first-class + facetable today.
- Real gap was presentation: GeneratedSource.rootGroup() always auto-derived a FLAT
  facet set from the schema and ignored the model's DECLARED GeneratedFacets — so an
  ordered drill-down like Category → year → nominee couldn't take effect. Fixed:
  GeneratedSource now loads the dataset's model (DatasetRegistry.modelPath threaded
  through registerAll) and uses DomainFacets.toFacets(class) IN DECLARED ORDER when
  any facet is declared; falls back to autoFacets otherwise. Generic, not
  Oscar-specific. Suite green.
- To get Category → year → nominee in the web: declare those three facets on the
  class via the workbench (SingleRootClassModelPanel "Suggest facets"/add), save the
  dataset (model + snapshot), restart the server.

## Dedup leak: dropped duplicates re-surfaced in the served snapshot
- Symptom: a Nomination per real co-nominee (Best Original Score → 13 people) is
  CORRECT, but the film-side & person-side of an acting nomination (e.g. Whoopi
  Goldberg / Best Actress / The Color Purple) appeared TWICE despite identical
  dedup keys (category,year,forWork,nominee). Snapshot audit: 17581 Nomination
  objects vs 15439 unique keys → 2142 redundant duplicates.
- Cause: reify promote() stamps the statement targetType IN PLACE, and the loader
  already stamps statements too; both copies stay in the pool and reachable. The
  served set is "every pooled object whose typeName==Nomination", so dedup filtering
  only its RETURN list left the dropped duplicates served.
- Fix (TransformEngine.applyReify, generic): after dedup, un-stamp (type(null)) every
  promoted statement NOT in the kept result, so it reverts to an anonymous referenced
  child and isn't served/listed. OscarReifyTest now asserts exactly one served event
  + one demoted sentinel. Suite green. Takes effect on next regenerate.

## Produced Nomination atom = the shared-award statement, nominees as a list
- Grain change so a SHARED award is visible (the user's "can't see if an award was
  shared"). Wikidata models it at the statement level: the work's film-side P1411
  statement carries ALL co-nominees as repeated P2453 qualifiers (Best Original
  Score = one statement, 13 nominees); separate nominations in the same category are
  separate statements (Best Supp. Actress = 2). The old per-nominee grain promoted
  the denormalized person-side copies instead → 27 rows for The Color Purple, sharing
  lost.
- #1 QualifierLoader: one statement object per ?st GUID (rows fold in), and a `multi`
  qualifier (QualifierLoadConfig.Qualifier.multi, driven by the field's Count:List
  cardinality) is MERGED into a list instead of overwritten. So P2453 → nominees list.
- #2 Canonicalize-by-list (ReifyConstruct.primaryListField + TransformEngine.canonicalize):
  a record WITH the nominee list = canonical (kept); a record without it but carrying
  an inverse role straight from a qualifier (person-side "for work" copy) = dropped
  (un-stamped); a record with neither = work-less/honorary, subject becomes sole
  nominee. ModelStatementReifications.derive marks the multi ENTITY qualifier as the
  primaryListField (not a fallback role); single ENTITY quals (forWork) stay
  subject-fallback roles.
- Result: The Color Purple → ~11 atoms; Best Original Score → 1 with nominees=[13];
  Best Supp. Actress → 2. Tests: OscarReifyTest (5). Suite 61 green.
- CONFIG (user, on UI): set Nomination.nominee Count: List (optionally rename
  nominees), Apply + Save, then regenerate. ENGINE reads that cardinality.

## Grammy leak + broken synthetic links + cap removed
- Broken wikidata link on reified Nominations: WikidataDynamicObject built a
  wikidata URL/source from ANY non-blank key, but a Nomination is keyed by a
  statement GUID (Q123-<guid>) → 404. Fixed: only a real entity QID (^Q\d+$) gets a
  link/source; statement-keyed synthetics get none. Generic.
- Grammy categories leaking into the OscarNominations `target` field: the
  auto-injected target field carries allowedQids (the membership's Oscar categories),
  but the query layer NEVER consumes allowedQids, so a generic relation (P1411,
  shared by the Grammys) returned every target. Fixed with a post-extraction prune
  (FieldValueRestrictions) wired into GenerateDomainQuery before inverts: a field's
  values are filtered to its allowedQids. Cheap (values already loaded). Test:
  FieldValueRestrictionsTest. (Proper query-layer VALUES restriction is a deeper
  follow-up.)
- Desktop instances cap disabled (MAX_CARDS=Integer.MAX_VALUE) — experiment whether
  the now-lightweight rows (link+reference rows on QuizableTextRow) render the full
  ~22k set acceptably. Reversible.
- Suite 65 green.

## Instances panel: GridBag → CardStackLayout (the relayout-on-expand cost)
- After caching QuizablePanel's preferred size (kills the re-MEASURE on relayout),
  the residual ~10s on expanding a chip in the uncapped (~22k card) panel was
  GridBagLayout.layoutContainer itself — it rebuilds grid-info + runs a
  constraint/weight solve over every child each pass.
- New CardStackLayout (quiz.ui): lays children out in component order, column i%cols
  / row i/cols, O(n) arithmetic, no grid solve. QuizablePanelView.createCardsPanel +
  addCardToGrid use it; the trailing Box glue is gone (preferred height = content
  height, scroll pane top-aligns). QuizableSearchPanel.applyTargetOrder now reorders
  by re-adding components in order (the layout follows component order) instead of
  setting GridBag constraints; detectColumnCount/setTarget read columns from the
  layout. Suite green.
- Net: expand triggers an O(n) relayout where only the changed card re-measures
  (cached sizes) — fast at any card count, so the desktop instances panel works
  uncapped as the configure→generate→test browser.

## Instances panel: virtualized rendering + data-centric sort
- QuizablePanelView now renders via VirtualizedCardList: holds the full ordered
  Quizable list, builds QuizablePanels only for the viewport (+buffer), per-card
  measured-height cache + cumulative tops[]. Single- AND multi-section views
  (MultiQuizableView) go through it. All quizables registered top-level up front
  (isTopLevel is data-based); QuizableRenderContext.topLevelResolver +
  VirtualizedCardList.buildIfNeeded make reference-chip navigation work to
  off-screen cards (build on demand, then scroll).
- Sort is now DATA-CENTRIC: QuizablePanelSearchAndSort.sortQuizables sorts the
  quizables by all fields -> virtualList.setItems(ordered) (recompute tops + visible).
  No component shuffle. QuizableSearchPanel.setTarget takes JComponent; targetPanel
  detects the VirtualizedCardList.
- KNOWN LIMITATION (next step): search still indexes/highlights only the live
  (visible) cards. Data-centric one-at-a-time search (match on data -> navigate hits
  via ensureVisible + highlight current) is the follow-up.
- Suite green.
