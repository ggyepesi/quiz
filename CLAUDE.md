# quiz

A Wikidata-backed modelling and quiz platform. You build a domain model against Wikidata in a
Swing workbench (ModelBuilder), generate a snapshot of real instances from it, curate that
snapshot, and serve it to a web client.

Universal standards live here. Conventions that apply only to certain files live in
`.claude/rules/` and load when you edit a matching path.

## Modules

- `app/` — everything domain-specific: the Wikidata extraction/generation pipeline, the
  transform layer, the Swing workbench, the curation and quiz code. A plain directory.
- `objectview/` — a **git submodule** (`ggyepesi/objectview`, branch `main`): the generic
  object-rendering library (Viewable, Card, SearchPanel, SearchableView, virtualization,
  media). It knows nothing about Wikidata, and must stay that way — app concepts reach it
  through annotations and contracts it already understands.
- `web/` — the web client. A plain directory.
- `docs/` — design notes worth reading before changing the thing they describe, notably
  `sparql-generation-rules.md` (WDQS rules R1–R18), `modelbuilder-constructs.md` (the
  conceptual anchor: Class / Statement / Selection, identity regimes, membership) and
  `serving-the-web-app.md`.

## Build and test

Always build through the reactor, never `-pl app` alone:

```
mvn -o -pl app -am compile      # builds objectview from source first
mvn -o -pl app -am test         # app + objectview suites
mvn -o -pl app -am test -Dtest='SomeTest' -Dsurefire.failIfNoSpecifiedTests=false
```

`mvn -pl app compile` on its own resolves objectview from the installed `~/.m2` jar, which can
be weeks stale — you get "cannot find symbol" errors in `app` that look like app bugs but are a
stale dependency. Same trap in IntelliJ if it resolves objectview as an artifact rather than a
module.

## Committing objectview

The submodule is committed and pushed **separately**, before the superproject. A root
`git add -A` records only the submodule POINTER, so objectview edits can otherwise sit
uncommitted while app commits that depend on them are pushed — a fresh checkout then compiles
against stale objectview.

```
cd objectview && git add -A && git commit && git push origin main
cd .. && git add objectview && git commit    # "Bump objectview: …"
```

`git status --short` showing a leading ` m objectview` means the submodule has uncommitted
content. Push the submodule and bump the pointer in the same session as the app commits.

Commit messages are a single declarative sentence about what is now true — "A kind is settled
before the parts that depend on it are made", not "Fix ordering bug". The body says what was
wrong and why this is the answer.

## Architecture directives

Hard-won from the provenance/`source` refactor. Each fires at a decision point; the trigger is
the tell that you are about to make the mistake again.

**Rule number 1 — keep it simple.** Start with the smallest model that expresses what the user
can see and control. Do not introduce hidden identities, parallel names, versions, lifecycle
states or indirection until a concrete requirement forces each one. A simple concept must remain
simple in the UI, persistence and explanation. *(Trigger: explaining an intrinsically simple
construct requires a vocabulary of implementation terms.)*

**Capstone — introduce nothing without a forcing reason.** The test is never "would this be
more complete, more honest, more future-proof" — it is "is there a forcing reason NOW". No
forcing reason, don't build it. Over-modelling, compatibility layers and speculative
abstraction are all symptoms of building past the forcing reason.

A "way to produce or derive something" is a **construct**. There should be ONE correct general
construct per thing produced:

1. **Fix the construct, don't except it.** When a general mechanism misbehaves for one case,
   improve the mechanism — never wrap it in a name- or type-specific exception. An exception is
   evidence the construct is wrong. *(Trigger: "I'll just special-case X here.")*
2. **A rename that relocates a symptom is not a fix.** Did this remove the cause or move it?
   *(Trigger: renaming to dodge a collision.)*
3. **One discovery path per concept.** The same fact must be derived one way. Two paths are a
   latent bug even while they agree. *(Trigger: "the schema comes from here, the rendering from
   there.")*
4. **Regenerate vs migrate is an explicit decision.** Don't default to compatibility. If the
   data is reproducible, regenerate clean; a translation layer is permanent pollution.
   *(Trigger: "but the old snapshots…".)*
5. **A reused mechanism must cover ALL its consumers.** Verify what it actually provides; a
   partial fit relocates the problem. *(Trigger: "we already have X, reuse it.")*
6. **Encode the principle as a forcing test.** A guard test that fails on new violations makes
   the rule self-enforcing (see `NameBasedRoleGuardTest`).
7. **Once the clean road is named, take it.** Intermediate shims are how a clean goal accretes
   a mess on the way. *(Trigger: "quick hack now, clean it up later.")*
8. **Survey what already exists BEFORE adding anything.** The structure is usually already
   there — go find it. Proactively, not as an after-the-fact check.
9. **Source identity is constrained by configured type.** A shared datasource identifier
   unifies copies only when the consuming field accepts the entity's configured class: the
   same class, a subclass, or an explicit contextual representation. Merely occurring in a
   field must never retype an incompatible entity; incompatible values are absent from both
   the saved dynamic pool and its rendered materialization. *(Trigger: "the QID is the same".)*
   *(Trigger: about to write a new class or mechanism.)*
9. **Inspection is not an action.** Selecting, highlighting, hovering or navigating may change
   inspection state only; it must not silently configure another tool, mutate the model, or
   start expensive work. A mutation is an explicit, verb-labelled command that names its target
   (for example, “Use selected property as edge”), and every command produces an immediate
   visible result or explains why it had no effect. *(Trigger: a selection listener writes state
   outside the view that owns the selection.)*
10. **The UI is the authority for user-authored configuration.** If a change can be made through
   the UI, an agent or background process must not make it on the user's behalf without explicit
   approval of that exact change. Inspection, validation and preview may not mutate.
   *(Trigger: "the user could configure this, but I can save them the step.")*
11. **Follow the design; a shortcut is a discussion, not a decision.** If a doc, a rule
   file or an explaining comment states how something works, read it before changing what
   it governs. Diverging is allowed and sometimes right — but it is raised explicitly and
   agreed, never taken quietly in the implementation. Every one of a recent run of UI
   defects was a design already written down and not read first: `MultiView.layout` says
   side-by-side is deliberate for simultaneous comparison, while a tabbed owner must
   register how it reveals a hidden target before navigation focuses that target;
   the pipeline design says a preview differs by scope and limits and never by dropped
   phases; `bounding-an-entity-end.md` says that construct carries population and never
   structure. *(Trigger: about to change something that has a design note or a comment
   explaining why it is as it is.)*
12. **Store the fact; do not derive it from something that merely agrees with it.** An
   implementation dependency the design does not state is a latent bug while it still
   agrees, and an unrepresentable state is its symptom — a class could not be a statement
   class until it had a property, because the kind was read off the property. If a
   decision can be made before its configuration is complete, it is stored. This sits
   beside rule 3 rather than replacing it: 3 forbids two routes to one fact, 12 forbids
   inventing a route where the fact should simply be kept. *(Trigger: "X is true whenever
   Y is", where nothing says X MEANS Y.)*
13. **Find the existing construct first — for small things too.** Not only classes and
   mechanisms: how a selection is handled, how a list and its chooser are built, which
   word the codebase already uses for the concept. Sharpens directive 8, which fires on
   new *mechanisms* and so let three hand-written copies of one list control through.
   *(Trigger: about to write a control, a phrase, or a name.)*

14. **When in the slightest doubt about what was agreed, ask — do not let the
   implementation settle it.** Directive 11 governs a design that is written down; this
   governs the gap where one is not. Reconstructing an agreement from memory and building
   it is how a design drifts without anyone deciding to change it, and the drift is
   invisible in review because each step looks reasonable on its own. Name the doubt,
   state the readings, and let the answer come back before the code does.
   *(Trigger: "I think we said…", or an implementation choice that no note, comment or
   message actually settles.)*

   The cost, measured: after a note that said every kind editor opens with its triple,
   the source editor shipped with the triple SEVENTH — behind a subtype control and a
   role list that both depend on it — the owned editor put it last, "Reifies statements
   of" survived as a free-text field that changes a class's kind by keystroke, and four
   panels ended in four different orders. No step of that was a decision; every one was a
   gap filled quietly.

15. **Give every kind of change an artifact that can see it, and look at the artifact.**
   A change is finished when something outside your own intent has confirmed it: for
   generated data the counts (`counts.tsv`), for an editor its rendered structure
   (`docs/panel-layout.txt`, enforced by `PanelLayoutIsCheckedInTest`). If no such
   artifact exists for what you are changing, build it FIRST — a change you cannot
   observe is a change you cannot claim, and a green suite is not observation when
   nothing in it can perceive the property you are designing.
   *(Trigger: about to report a UI or structural change as done with only a passing
   suite as evidence.)*

   The asymmetry that proves it: the same session verified a membership reshape against
   real data — 634 prizes, not 636 — while the panels drifted for a day, green
   throughout, because no test could see that the triple had landed seventh on one
   editor and last on another.

16. **Tell the user straight what is happening and what will happen next.** Do not
   reformulate, euphemize or substitute a more general description for the actual
   operation. Before work starts, name the work that will be done; while it runs,
   continuously name the work being done now and the work that follows. Loading and
   saving a domain, model, instances, cache or run log names the exact file being read
   or written. A running operation must not leave the user with only “Running…” or an
   unchanged log when the application knows the current stage. *(Trigger: the user has
   to ask whether a running operation is loading, downloading, recomputing, saving or
   stuck.)*

   An action that continues after its click immediately disables its trigger and names
   the current work on the button or adjacent status. Expensive work runs off the Swing
   event thread so that state can actually be painted, and every success or failure path
   restores the action state.

17. **Same concept means the same processing and the same UI.** A preview, saved
   result and subsequently loaded domain are not three representations that may merely
   resemble one another: they use the same instances and the same declared model through
   the normal domain workflow. A result is applied to project state; **Save model/domain**
   is the single persistence boundary for configuration, generated instances and named
   run results. Closing a result does not secretly apply or save it.
   *(Trigger: introducing a special result renderer, inferred substitute schema, or a
   workflow verb that differs from the operation it performs.)*

   Save and Load are concepts, not local button implementations. Every save/load entry
   point uses the shared persistence confirmation UI and names the domain, instances,
   model/types and exact files that the specific operation will read or write. TransformApp
   shows the owning project's Domain/Model kind; saving a model-backed working domain keeps
   that kind and writes its semantic subclasses to the owning model and ordinary snapshot,
   never to a detached same-name transform dataset.

   **QuizFactory and the web serve saved configured domains only.** Their catalogue is the
   registry's saved `served` snapshots and each loaded domain's `servedTypes`; they never
   carry a second hard-coded domain/class list. Live hand-written domains remain available
   in TransformApp as conversion inputs, and enter QuizFactory only after **Save domain**.
   A model snapshot is local working data: its registry entry stays, marked not served, so
   TransformApp can still load it; changing a domain to a model stops serving it without
   removing those local instances. Every save writes that one registry state.

18. **A reusable instance population is a `PopulationSelection`.** It stores one class
   name and the stable datasource identities of explicitly chosen instances, never copies
   of their mutable objects. Sampling and highlighting only edit the draft; saving is an
   explicit action that names the selection, count and model file. Graphs consume this same
   saved construct rather than owning another QID list. Statement subject/object bounds also
   consume it through the same named-selection reference used by vocabularies; they do not
   require the same QIDs to be copied into a second `VocabularySelection`.

   A **transformed class is not a population selection and not an ordinary subclass**.
   Its saved transformation is its population producer and its typed instances are a
   materialized output owned by the model's companion snapshot. A base class contributes
   schema but never fallback membership. TransformApp edits/previews the shared headless
   transformation configuration; ModelBuilder may invoke the same executor through a
   datasource adapter, but neither application owns a second execution path or automates
   the other. See `docs/transformation-models-as-datasources.md`.

   **Imports cross the instance boundary only through a referenced population.** An
   imported class contributes its configuration and may be used as a field type, but its
   owner's class snapshot is not imported and its original population rule is not rerun
   by the consuming project. When one of the consuming project's own constructs names an
   imported `PopulationSelection`, generation materializes exactly those saved QIDs as
   instances of the population's class. Unreferenced populations remain declarations,
   and several referenced populations of the same class contribute their union.

   A TransformApp relation-closure group is a saved produced-group rule over the loaded
   object graph. It names a bridge class, the bridge fields leading to group members and
   traversal entities, an admission `PopulationSelection`, and explicit seed entities.
   Seeds reach members even when outside the admission population; only admitted traversal
   entities may continue the walk. The group contains the reached members, renders through
   the ordinary ObjectView group panel, and performs no datasource requests.

   **A statement's subject-discovery objects and retained objects are separate bounds.**
   A saved population may answer “which objects do we use to find subjects?” without
   answering “which object values do we keep on those subjects?”; generation must not
   silently reuse one answer for both questions. This lets a controlled position population
   discover its complete holder population while retaining the first outside positions as
   the boundary. Such retained entities are classified through the ordinary subclass
   condition: a subclass may say that its base instances' datasource QIDs are inside or
   outside one saved `PopulationSelection`. It is local, idempotent classification over the
   generated object graph, not a second population query and not a special graph run.

19. **A graph constraint is a class of kind `GRAPH`; only its pipeline differs.** Its
   identity, its name, rename propagation, its place in the classes list, its editor and its
   persistence all come from the class construct, and its instances are its annotation set.
   Its configuration is a `GraphClassSource` — start node, next nodes, evidence condition —
   the sibling of `StatementClassSource` and `AggregateClassSource`. It is named where every
   class is named, so there is no name of its own beside the class's: the name that keys the
   annotation file and the name shown in the editor are one fact, and `declarationId`
   underneath it is what a rename does not move. **A project may declare several**, because a
   domain needs more than one discovery rule — one narrowing the offices worth asking about,
   another reaching their holders. Its ordered next-node path is authored and shown whole;
   earlier nodes are intermediate and its terminal node produces instances of exactly one
   configured class, which is never itself or another graph class. The annotation instances
   reference those candidates and the candidates carry a hidden reverse reference, so
   ObjectView/MultiInstance renders one connected object graph. Result tabs keep the graph's
   original Accepted/Review/Rejected classification; manual accept/reject is a separate
   override mark and never rewrites or moves that original result. **Those tabs render the
   exact output-class candidate as the card, with the original decision, manual override and
   per-candidate reason as decoration; graph configuration and traversal bookkeeping stay on
   the hidden annotation, not as a second user-facing object.** Applying NARROWS the output
   class to the accepted population, keeping the project's own instances whole — it never
   replaces them with candidate shells. Population selections are created from class
   instances, not from the graph editor. The annotation snapshot lives beneath the owning
   project and is written only by Save model/domain. **Show instances has one window and one
   ObjectView for ordinary classes and every named graph-annotation class; selecting a graph
   class does not switch to the run-results workflow. Each graph constraint is one class tab
   with `All`, `Accepted`, `Review`, and `Rejected` ObjectView subtabs; those partitions read
   the original graph decision, while manual overrides remain marks.** Saving unchanged graph configuration
   retains its completed result; only changing that graph's configuration invalidates it. A
   class graph input means all currently
   loaded instances of that class, while a saved population is one alternative input that
   already names its class.

   A graph population result states whether it **narrows** its output class or **adds**
   memberships to it. Additive expansion preserves every existing member and stamps the
   reached generated instances with the output class; it never substitutes candidate
   shells. Accepted identities that have no generated instance remain pending on the applied
   annotation set; the next Generate run acquires them as a supplemental population through
   the ordinary class pipeline, unioned with rather than replacing the configured population.
   Closing without Apply contributes nothing. A replacement-chain expansion may declare equivalent directed edge alternatives
   (for example outgoing `replaces` and incoming `replaced by`) and repeat them until no new
   identities are reached. A next node may name a `PopulationSelection` as its admission
   boundary: reached identities outside it remain Rejected annotations with the population
   failure and reachability bookkeeping, but are never expanded, including during a repeated closure.
   The selection is an ordinary referenced/importable population, not a graph-owned QID list.
   This is a separate named graph rule, not another meaning hidden inside the original
   population-discovery constraint.
   Each entry records what it was reached from; an entry counts toward the result only while a
   chain from the start reaches it through entries that are not rejected — by hand or by
   evidence — so rejecting one takes what was reached only through it out, without a rerun.
   Waiting for a decision is offered only on the output node, whose entries are the result.

   *(This supersedes the earlier rule that a graph constraint carries its own authored name.
   That name was at once the identity of its annotation set and a free-text field an editor
   rewrote, which is how a run saved as PositionFilter came to sit beside a model calling
   itself GraphConstraint with nothing able to notice. Refusing a silent default treated the
   symptom; the cause was a construct with no identity apart from its name.)*

20. **Model reuse and local execution are independent.** `MODEL` means its declarations
   and relevant selections may be imported; it does not mean the project cannot generate.
   A model whose acquisition configuration is complete may generate, load and save its own
   local instances for curation and graph work. Graph constraints are likewise runnable
   whenever their own configuration and instance/population input are ready. Imports read
   configuration and selections only — never another project's snapshot, graph annotations
   or other run results. An imported selection stays owned by its model and is read-only in
   the importer, just like an imported class; edit it in the model that owns it.

21. **Persist an expensive graph before fallible local materialization.** Once generation
   has finalized its shared object graph, write that exact graph to the named recovery file
   before mapping generated Java instances. Remove it only after mapping succeeds. A later
   Generate action explicitly offers to load that file and resume materialization without
   repeating remote acquisition, and rejects it when the model fingerprint differs.
   *(Trigger: a local mapping/rendering defect would otherwise require another Wikidata run.)*

22. **A delivered project is a headlessly executable artifact build.** ModelBuilder and
   TransformApp edit and inspect declarations; neither application is the execution boundary.
   Generation, graph run, graph-decision application, transformation, population publication
   and project save are elementary operations accepting compiled configuration and explicit
   typed inputs. A thin Make-style coordinator orders them by named artifact dependencies,
   reuses compatible outputs, explains staleness, and publishes the final servable result
   without loading UI classes. Review remains explicit persisted data: an unattended build
   either has complete decisions/a declared non-interactive policy or stops at
   `AWAITING_DECISION` without replacing the last complete output. See
   `docs/transformation-models-as-datasources.md`.
   *(Trigger: a complete domain can only be reproduced by manually switching applications or
   by replaying UI actions.)*

23. **The current construct inventory owns every instance artifact.** Class, graph,
   population and vocabulary selection are one lifecycle concept: a construct with a
   stable declaration id. Load instances, Show instances and Save domain/model all use
   the same current in-memory inventory to project the object pool; no editor maintains a
   private list or deletes a snapshot. A confirmed edit changes memory only. Save makes
   the on-disk manifest exactly the current inventory, writes the projected instances and
   removes only files owned by declarations no longer present. Cancelling a later action
   cannot restore a previously removed construct, and closing without saving leaves disk
   unchanged. The manifest is written last so it never claims a partial save completed.
   *(Trigger: a class/graph/selection/population has its own load, show, removal or save
   rule, or a configuration edit deletes instance files before Save domain/model.)*

24. **Modularity: express a construct in simpler existing ones, immediately.** When a
   construct can be written in terms of simpler constructs that already exist, rewrite it
   into them once, at the boundary where it enters (creation, load, edit), and let every
   consumer see only the simpler form. A shorthand that survives past that boundary forces
   each consumer to interpret it, and each interprets it slightly differently: `allFields`,
   `allMinorFields` and an absent child config were read by the editor, Card, ValueRenderer,
   the table, the web serializer and quiz extraction, each with its own fallback, while the
   same config looked like a plain list of ticks to the person who wrote it. *(Trigger: a
   flag, alias or "absent means …" that more than one consumer branches on.)*

## Working agreements

- **Build features by assembling pieces that already exist.** Defer new automation until
  something really forces it.
- **Quiz types share selection semantics.** Question/answer indexing, correctness and
  exhaustion are common quiz-domain processing; a quiz type owns only its genuinely distinct
  layout and interaction. A fixed-size choice set is constructed once and rendering never
  filters it afterwards. Exhaustion may decide whether an answer is still owed as a correct
  response or how it is marked, but an exhausted value remains a valid distractor. Selected
  keys are also the content boundary: the quiz engine assembles the exact query and answer
  objects that produced each key and retains their source-instance identity for scoring. Each
  assembled object contains only the configured fields; an unselected display label is explicitly
  empty. The same query/answer `ViewConfig` is reused for field order and presentation — there is
  no second mapping — while the assembled object's absent fields enforce the selection. ObjectView
  must not rediscover the tuple. Selecting a collection keeps the whole selected collection in
  the assembled object. Key extraction may enumerate its members, and fields selected below an
  object collection stay correlated within the same member, but that enumeration never trims the
  collection presented by the assembled query or answer object. Selected
  object fields and their child fields are independent selections. Selecting an object field
  alone contributes its caption but no value path; its display or other nested fields enter the
  quiz key only when the user explicitly selects them, so selecting a
  self-reference such as `Person.spouse` never recursively selects another `Person` graph.
  Selected multi-value fields expose their Cartesian combinations as a lazy random-access view: the
  shared quiz index retains compact alternatives and materializes only a requested key, never
  the complete product or one copy of the same answer product per question key. If one
  instance still exceeds the indexable combination limit, quiz creation stops without a
  partial index and reports that instance together with the contributing fields and their
  value counts.
- **ObjectView DISPLAY is an ordinary field with one presentation role.** It is selected,
  hidden, searched, sorted and projected by the same `ViewConfig` rules as every other field.
  When selected, its one painted occurrence is that object's title/caption; it is not repeated
  as a body row. The caption carries the DISPLAY field path/value for search and highlighting.
  When unselected there is neither field value nor caption and no
  fallback through `getName`, `getDisplayName` or a reference label, while every other child
  still follows its own tick.
  Selecting an object field never selects DISPLAY (or any other child) implicitly. In a
  quiz key an object with no selected children contributes its field-name caption alone; in
  a View, search or sort config an object or object collection is a branch, present only
  while something below it is selected (or it inherits), so neither a caption alone nor
  `field name (size)` alone is a View state. An older config that ticked one alone is
  converted where it enters (`ViewConfigDesugar.connected`), never by a renderer. An
  explicit empty child config is not a legacy shorthand for a display-only or all-fields
  config. A newly created default View config is the one exception to that
  word "implicitly": it ticks every top-level field and the DISPLAY of each object they
  lead to (nothing deeper), and an `@Inline` object's own fields one level deep, since it
  is part of its owner — all as ordinary ticks a reader can untick (`ViewDefaults`). A
  field selection's default (search, sort, quiz key) ticks nothing below an object.
  A field whose declared type is already on its config path — the editor's ↩, the root
  included — has no config of its own: it inherits the nearest ancestor's of that type
  (`ConfigChain`), written into the config where it is made. An inherited occurrence,
  object or collection, starts folded, so each expand opens exactly one level and only the
  reader's clicks set the depth; an object already on the path ends it. Search and sort
  read through the same inheritance, each object once per level from a root; a quiz key
  never inherits. A sort compares a path's values one by one in the order it reads them
  (#368). A finite field selection records that fact on its `ViewConfig`; a renderer must
  not infer View recursion from the same literal field tree. This keeps one config usable
  for both quiz projection and presentation without reinterpreting its selected paths.
  Unticked means absent for every field and field role. A ticked collection
  always renders `field name (size)` immediately, including size zero, and renders its members
  lazily only when expanded; each member recursively follows the same config. Whether it starts
  expanded is a presentation policy independent of selection: singleton media and explicitly
  inline workflow content remain immediately visible, while ordinary collections start folded.
  Render hints, backing type and card/table layout may change presentation, never these selection
  semantics. A scalar reference whose target already has a top-level card is navigation only and
  never repeats that target's configured fields inline; with its DISPLAY unticked the
  link reads “Open”, naming the action and never the object or its type. An explicitly
  expanded object collection is different: its members are the collection's configured
  projection, so top-level registration never suppresses their selected child fields. A
  member of a reference collection whose target has a top-level card keeps that projection
  and its caption navigates to the card; its separate disclosure glyph still expands or
  collapses the projection. DISPLAY selection controls neither navigation nor whether those
  collection-member fields render.
  **The Search, Sort and View editors admit only connected nested selections.** A raw
  value or collection of raw values may be a terminal tick. Ticking a nested value
  activates every object/collection owner on its path; an object owner cannot be
  activated without a selected nested field. Unticking an owner suppresses its whole
  configured branch without erasing it: remembered descendant ticks stay visible but
  disabled, are persisted, and return when the owner is reticked. Removing individual
  descendants, or the explicit **Clear fields** action, is what forgets them. Search
  may still find a field hidden by View; navigating to the current hit temporarily
  reveals that path without changing the saved View configuration.
- **Ask in prose.** Decisions come as a recommendation plus the trade-off, not a multiple-choice
  menu.
- **Confirm cancellation.** A user-facing Cancel action on running work defaults to keeping the
  process alive and stops it only after an explicit confirmation.
- **Guard application close.** Closing a workbench defaults to keeping it open when a process is
  running or configuration/generated results are unsaved. Cancelling work, saving, or discarding
  must be an explicit choice that names what will be lost.
- **Name an open thread before switching.** If a new direction arrives while another is
  unfinished, say what is open and ask: finish, park, or run in parallel.
- **File a GitHub issue for every non-trivial agreement** — a design decision, a deferred
  feature, a bug found but not fixed:
  `gh issue create --repo ggyepesi/quiz --title … --label …` (labels in use: `bug`,
  `enhancement`, `question`, `modelbuilder`, `webclient`). For work resolved in the same
  session, file it and close it with how it was fixed, so there is an audit trail.
- **Every generation knob** (membership, filters, ordering, label requirement) should be
  discoverable and editable in the ModelBuilder UI, not only in code.
