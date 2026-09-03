# Bounding an Entity End

## Status

Design note, no code. Works out one construct — how an end of a statement triple is
bounded — used identically for the subject and the object. Written after a survey found
most of it already exists, one layer down.

Companion to [[the-subject-of-a-statement-is-a-role.md]] (which owns the three axes and
their owners) and [[modelbuilder-constructs.md]] (which owns the vocabulary).

## What this is, and what it is not

This construct carries **population only, never structure**.

```text
structure   which field receives the subject/object, and its placeholder class
            → the model owns it; an importer FILLS what was left open
bounds      which entities may occupy that end at all          ← THIS NOTE
            → optional everywhere; the domain owns it; an importer overrides freely
```

Keeping the two apart is what makes the construct safe to hand to importers wholesale: an
override can never reshape a class, only change which entities it draws.

## The problem, measured

Bounding an end is spelled four ways today, and the ends are not symmetric.

| control | where it lives | what it means |
|---|---|---|
| Subject population | `StatementClassSource.sourceClassName` | another class, whose members are the subjects |
| Allowed objects | `StatementClassSource.valueSelectionName` | a VOCABULARY Selection's QIDs |
| Object type filter | `GeneratedClassModel.instanceMapping().sourceQid()` | ONE QID; object must be `wdt:P31` of it |
| — | `FieldSourceMapping.allowedQids` | explicit QIDs on the field |

Two defects follow, and both are the kind this codebase keeps rediscovering.

**Silent precedence.** The object bounds do not combine. `QualifierLoader`:

```java
if (cfg.hasValueQids())       allowedValues = valueQids;               // type filter IGNORED
else if (cfg.hasValueType())  allowedValues = instances of that QID;   // one SPARQL query
else                          allowedValues = null;                    // anything
```

Configuring an allowed-objects vocabulary *and* an object type filter does not intersect
them — the QID set wins and the filter silently does nothing. Worse, a vocabulary that
carries its own value type overwrites the class's filter before that even runs. Two
controls that look combinable, one quietly discarded.

**Asymmetry.** The object end can be bounded by QIDs directly; the subject end cannot. The
only way to bound a subject is to point at another class that exists for the purpose. That
is why the subject control needed a name of its own — "Subject population" — for what is
the same question asked at the other end.

## The construct already exists, one layer down

`datasource.api.acquisition.PopulationSelection` is a provider-neutral bounded set of
entities, and it already models the alternatives as mutually exclusive **and enforces it**:

```java
enum Kind { RELATION, EXPLICIT }
RELATION   relationId + targets + includeDescendants     "P31 = Q5", optionally with P279 closure
EXPLICIT   a list of EntityRefs                          exactly these entities
// the constructor REFUSES a RELATION without a relationId, and an EXPLICIT one that has one
```

That is precisely the "list to select one" shape, made unrepresentable rather than merely
discouraged. So the work is not to invent an enum — it is to make the authored model
compile into this one, and delete the ad-hoc precedence above it.

Note `includeDescendants`: P279 closure, which today's single-QID type filter cannot
express at all. It arrives for free.

### How the authored modes map

```text
anything                    no bound
these QIDs                  EXPLICIT   the QIDs
a vocabulary                EXPLICIT   its valueQids
                            RELATION   P31 → its valueTypeQid, when that is what it carries
instances of a type QID     RELATION   relationId = P31, target = the QID
instances of a class        NOT a fifth kind — an indirection to whatever bounds THAT class
```

The last line matters: "instances of a class" is how the subject end is bounded today, and
it is not a distinct way of naming a set. It defers to another class's own bound, which is
one of the four above. Treating it as a mode would be a fifth construct for something
already expressible.

## What changes in the model

One authored value per end, replacing four controls:

```text
StatementClassSource
    subjectBound : EntityBound      // new — the subject end, today unrepresentable
    objectBound  : EntityBound      // replaces valueSelectionName + the class's sourceQid
```

`EntityBound` is the authored form: a mode plus its one value. It compiles into
`PopulationSelection`. It is **optional on both ends** — absent means unbounded, which a
model may leave and a domain must resolve before generating, exactly as the subject
destination rule now works.

### What happens to what exists

- **`sourceClassName`** becomes the `instances of a class` mode of `subjectBound` rather
  than a field of its own. Same information, no longer a special case.
- **`valueSelectionName`** becomes the `a vocabulary` mode of `objectBound`.
- **`instanceMapping().sourceQid()` as an object type filter** becomes the
  `instances of a type` mode. This one deserves care: `sourceQid` on a class means "the
  class's own membership type" everywhere else, and a statement class was reusing it for
  its object. That reuse is itself a violation — one field, two meanings — and moving it
  out is the fix, not a side effect.
- **`FieldSourceMapping.allowedQids`** stays. It bounds an ordinary field's values, which
  is a field concern; the object end delegating to it was the accident.

### Regenerate, do not migrate

No saved model currently persists anything that changes shape here except
`valueSelectionName` and `sourceQid`, both reproducible. Per directive 4 this is a
regenerate, and the authored configuration is corrected in the UI — which is the only
place it may be corrected.

## The two `PopulationSelection`s

There are two classes with this name, and they are **not redundant** — they sit at
different layers:

| | `wikidata.explore.model.PopulationSelection` | `datasource.api.acquisition.PopulationSelection` |
|---|---|---|
| what | an AUTHORED `Selection` the modeller names and saves | a RESOLVED instruction handed to a provider |
| holds | `relationPid` + `targetQids` | `Kind` + `relationId` + `values` + `includeDescendants` |
| lives in | `model.json`, under `selections` | memory, during a run |
| validity | a free-form bean; anything can be half-set | enforced in the constructor |

So the layering is right and the note above depends on it — authored form compiles into
acquisition form. **The problem is only the shared name.** It is already costing: four
places in `app/src/main` must write `datasource.api.acquisition.PopulationSelection` in
full because the short name is taken, and at a glance no reference tells you which layer
it belongs to. That is the vocabulary rule's exact failure mode — one name, two things.

**Rename the datasource one**, not the model one, for a checkable reason: selections
persist by fully-qualified class name (`"@class": "wikidata.explore.model.
VocabularySelection"` appears in every saved model), so renaming the model-side class
would break saved models the moment one exists. The datasource record is never persisted.

A caveat that cuts the other way: `datasource` is the candidate open-source boundary
([[open-source-boundary.md]]), so renaming it is cheaper now than after it is published.
Both reasons point the same way.

Suggested name: **`PopulationRequest`** — what a provider is asked to fetch. Not
"Compiled…", because this package is provider-neutral rather than part of the compiled
model.

### One thing to check before relying on the model-side one

`wikidata.explore.model.PopulationSelection` is read by `SelectionContentResolver` and
rendered by `SingleRootClassModelPanel`, but **nothing constructs one outside tests**, and
no saved model contains one — every persisted selection is a `VocabularySelection`. So it
may be an aspirational construct rather than a live one. Worth deciding deliberately: if
`subjectBound`'s `instances of a class` mode covers what it was for, it should be deleted
rather than left as a second way to say the same thing.

## Sequence

1. ~~Rename the datasource record~~ — **done**: `PopulationRequest`.
2. ~~Introduce `EntityBound`~~ — **done**: `QualifierLoadConfig` carries one
   `objectBound` in place of the two fields that competed, resolved once at compile.
3. ~~Delete the `if/else if` precedence~~ — **done**: `QualifierLoader` switches on the
   bound's kind, which is total and has nothing to rank.
4. ~~Give the subject end its bound~~ — **done**: `StatementClassSource.subjectBound`,
   carried through compilation into the discovery query, which now pins BOTH sides of
   the join (R16) instead of only the object side. Either end bounded now satisfies the
   discovery guard, in the loader and in validation — the old rule named the object end
   because it was the only end that could be bounded.
5. One editor control per end, in the triple box, offering the modes as a list.
6. Decide the fate of the model-side `PopulationSelection`.

Steps 1–3 change no behaviour on a correctly configured model; step 3 changes behaviour
only where a model set both object bounds, which is the case that is silently broken now.
