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

## Working agreements

- **Build features by assembling pieces that already exist.** Defer new automation until
  something really forces it.
- **Ask in prose.** Decisions come as a recommendation plus the trade-off, not a multiple-choice
  menu.
- **Name an open thread before switching.** If a new direction arrives while another is
  unfinished, say what is open and ask: finish, park, or run in parallel.
- **File a GitHub issue for every non-trivial agreement** — a design decision, a deferred
  feature, a bug found but not fixed:
  `gh issue create --repo ggyepesi/quiz --title … --label …` (labels in use: `bug`,
  `enhancement`, `question`, `modelbuilder`, `webclient`). For work resolved in the same
  session, file it and close it with how it was fixed, so there is an audit trail.
- **Every generation knob** (membership, filters, ordering, label requirement) should be
  discoverable and editable in the ModelBuilder UI, not only in code.
