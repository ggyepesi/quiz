# Qualified relation events — a reusable ModelBuilder configuration pattern

## Motivation

Several domains begin with different subject matter and arrive at the same Wikidata
shape:

```text
subject ── property ──▶ value
              │
              └── qualifiers describing this particular relation
```

The relation is the domain record. Its qualifiers do not describe the subject or value
in general; they describe one occurrence of the relation between them.

Nobel prizes and Oscar nominations make the parallel visible:

| Structural role | Nobel | Oscars |
|---|---|---|
| subject | laureate | nominated work/person |
| property | award received (`P166`) | nominated for (`P1411`) |
| value | Nobel category | Oscar category |
| temporal qualifier | award year (`P585`) | ceremony/edition (`P805`) |
| descriptive qualifier | award rationale (`P6208`) | work/role qualifiers |
| promoted record | `NobelPrize` | `Nomination` |

Neither promoted record is simply the subject or the value. One laureate can receive
several prizes; one nominee can have several nominations. Flattening qualifier values
onto the subject loses which relation they belonged to.

History supplies the next forcing examples:

- a person holds a position (`P39`), qualified by start/end dates, jurisdiction,
  predecessor and successor;
- a person serves as President of the United States, one bounded position within that
  more general office-holding relation;
- a person is a spouse (`P26`), with start/end time or other relationship qualifiers;
- an entity participates in an event through a qualified relation.

These are not reasons to copy a Nobel or Oscar model. They are evidence that
ModelBuilder needs one reusable configuration pattern.

## Proposed pattern

Working name: **Qualified relation event**.

A configuration declares:

1. **Subject population**
   - reuse members of a configured source class; or
   - discover subjects directly from the relation.
2. **Relation property**
   - the property whose statements become records.
3. **Value domain**
   - a class, vocabulary, explicit selection or other bounded population;
   - required for direct subject discovery.
4. **Promoted record class**
   - the domain name for one statement, such as `NobelPrize`, `Nomination` or
     `OfficeHolding`.
5. **Role fields**
   - statement subject;
   - statement value;
   - selected qualifier values.
6. **Identity grain**
   - an explicit key over subject, value and distinguishing qualifiers.
7. **Presentation policy**
   - display-name field/template;
   - optional primary collection for canonicalizing source-denormalized copies.
8. **Discovery policy**
   - none, bounded preview or curated graph frontier.

The pattern compiles to the existing StatementClass/reification machinery. It is a
configuration construct, not another runtime representation.

## Common skeleton and domain-specific choices

The shared pattern must not erase meaningful differences.

| Decision | NobelPrize | Nomination | OfficeHolding |
|---|---|---|---|
| successful by existence? | yes | no | yes |
| companion result needed? | no | `won` may be derived | no |
| statement value | award category | nomination category | position |
| main temporal value | year | ceremony/edition | start/end dates |
| subject role | laureate | nominee | holder |
| descriptive evidence | motivation | work/role | jurisdiction |
| likely key | laureate + category + year | nominee + category + ceremony/work | holder + position + start |

The reusable construct supplies the structural questions and validation. Each domain
supplies the answers.

## Configuration UI

The UI should present the pattern as one explanatory diagram and one ordered workflow:

```text
[Subject / discovered subjects]
              │
              ▼
 [relation property] ─────▶ [value domain]
              │
              ▼
       [promoted record]
       ├── subject field
       ├── value field
       ├── qualifier fields
       └── explicit identity key
```

The diagram is both explanation and navigation. Selecting a node opens the one editor
that owns it. The workflow remains explicit:

1. discover the relation from examples;
2. declare and bound the relation;
3. preview representative statements and qualifier coverage;
4. choose fields and cardinalities;
5. review the proposed identity grain;
6. generate;
7. inspect and apply the result.

No action should infer a model-changing choice merely because the user highlighted a
property or entity. Explore records reusable selections; configuration consumes them
through an explicit action.

## Validation supplied by the pattern

One shared validator should reject or warn about:

- direct subject discovery without a bounded value domain;
- no field representing the statement value;
- no explicit way to represent the statement subject;
- qualifier configuration on a non-statement field;
- a key containing values produced only after reification;
- a collection used in identity without an explicit canonical-list policy;
- unsafe subject/value fallback hidden behind a missing qualifier;
- two fields claiming the same structural role;
- a role target whose configured kinds contradict the field's allowed types.

These checks currently emerge in different panels and runtime derivations. The pattern
should make them one contract.

## Discovery and preview

Explore should be able to propose this pattern from a representative entity:

1. choose a relation such as `P166` or `P39`;
2. sample its statements, not only its direct values;
3. report qualifier PIDs, coverage, cardinality and example values;
4. show candidate value domains;
5. offer **Configure as qualified relation event**.

The proposal is read-only until the user applies it. A preview should show concrete
records—for example Einstein's Physics prize or one presidential term—beside the
structural diagram.

## Relation to graph discovery

A qualified relation event describes the edge record. Graph discovery describes how
selected edge values become a frontier and how that frontier is expanded.

They compose naturally:

```text
Qualified relation event: Person ──P39──▶ Position
Graph discovery:                         Position ──configured steps──▶ Position
```

For History, `OfficeHolding` preserves dates and jurisdiction while graph discovery
can expand selected positions through succession or other configured relations. The
event pattern must not imply graph expansion, and graph reachability must not imply
served membership.

## Implementation path

1. Make statement subject and statement value explicit field-source roles.
2. Extract a provider-neutral `QualifiedRelationEventSpec` from the declarations that
   StatementClass already carries implicitly.
3. Compile both the current model and the new UI through the same spec; do not create a
   second reification path.
4. Move structural validation to the spec/compiler boundary.
5. Add a shared diagram and example-first preview.
6. Migrate Nobel, Oscars and History configurations by proving generated recipes and
   served records remain equivalent.
7. Only then offer **Configure as qualified relation event** from Explore discovery.

## Acceptance examples

The abstraction is ready when the same configuration skeleton can express all three
without domain-name branches:

### Nobel

- discover P166 subjects bounded by six Nobel categories;
- preserve laureate, category, year and motivation;
- do not invent a win flag or prize share.

### Oscars

- preserve nominee, category, ceremony/work and role qualifiers;
- allow a separately derived winner result;
- canonicalize source-denormalized copies explicitly.

### History / US presidents

- promote P39 statements whose value domain includes President of the United States;
- preserve holder, position, start/end, jurisdiction and succession evidence;
- allow the position domain to participate in separately configured graph discovery.

If one of these needs a hardcoded class name, property name or special pipeline branch,
the common pattern is not finished.

