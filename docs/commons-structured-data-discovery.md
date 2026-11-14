# Wikimedia Commons structured-data discovery

## Purpose

Commons Discovery is the read-only **Commons depictions** tab of the Discovery
workbench (`docs/discovery-workbench.md`), answering:

> Which Wikimedia Commons files explicitly depict these exact modeled entities, and
> what complete structured depiction scene does each retained file describe?

It does not add fields, alter instances, save groups, or infer that visually similar media
depicts an entity. A later source recipe can be designed from observed coverage without
turning discovery into a second extraction path.

## First bounded workflow

1. Run `discovery.DiscoveryApp` from the `app` module.
2. Open a saved generated project through the shared saved-project navigator.
3. Choose a class whose loaded instances have Wikidata QIDs.
4. Start a random sample, then add or remove instances in the workbench's two ObjectView
   selection panels, and open the **Commons depictions** tab. One Commons run accepts at
   most 20 exact QIDs; a larger selection disables Fetch and says how many to remove.
5. Choose the visible maximum number of Commons files per QID (20 by default).
6. Press **Fetch N selected entities**. The action states the selected-instance and distinct
   QID counts, the per-QID cap, the maximum file count before duplicates are merged, and
   that the project is not changed.
7. Inspect:
   - **Connected objects**, a shared ObjectView `MultiView` containing the original domain
     instances and the resulting media objects. `MediaRepresentation.selectedEntities`
     refers to those same instances, so a reference navigates to its original card. Each
     media object contains every retained structured depiction and its qualifiers;
   - **Groups**, with read-only facets by selected entity, co-depicted entity, supported
     depiction qualifier value, and field coverage;
   - **No structured depiction**, containing exact selected instances for which the bounded
     query returned no file.

Each media object exposes its image, linked Commons file page, English caption (falling
back to the filename), exact selected instances, every non-deprecated depicts statement,
prominence, supported qualifier values, main subject, digital representation, QID-valued
creators, creation dates, medium, collection, and location/institution when Structured Data
on Commons provides them. Every co-depicted entity remains a linked `WikidataSource`, even
when a loaded instance carries the same QID: a shared datasource identifier does not decide
which modeled class that entity belongs to (directive 9), so discovery neither manufactures a
domain instance nor attaches the depiction to an existing one.

The supported depiction qualifiers are the Commons-documented bounded vocabulary: shown
with features, color, wears, quantity, inscription, symbolizes, relative position, shape,
material, applies to part, hair color, uses, expression/gesture/body pose, determination
method, and depicted part. The query spells these predicates out because an unbounded
variable-predicate scan is prohibitively expensive on the Commons endpoint.

## Acquisition and bounds

The query uses the programmatic QLever Wikimedia Commons endpoint recommended by the
Commons query-service documentation. Acquisition has two visible, logged stages:

```text
selected QID -> capped Commons files whose depicts (P180) equals that QID
retained image files -> file metadata
retained image files -> all non-deprecated P180 statements, ranks and supported qualifiers
```

Metadata and the depiction scene are separate requests per file batch: in one query every
metadata value multiplied every depiction and qualifier row of the same file.

The shared batch executor owns both stages. It splits the selected-QID request when needed,
then reads retained files in batches of 25 with up to two requests in flight, splitting a
failed file batch without changing the result. It never widens the input, follows an inverse
relation, uses a co-depicted entity to discover more files, or silently raises a cap. Query
logs show every issued SPARQL request and its elapsed time; running work has an explicit
cancel action and closing the window asks before cancellation.

Only image files are shown as depictions. Audio, video and documents also carry depicts
statements but would render as a broken image; they still count toward the per-QID cap, and
the result states how many were not shown.

Duplicate files found for several selected QIDs become one `MediaRepresentation` with
several selected-entity references and one shared depiction scene. This keeps the Commons
file, rather than a file/subject pair, as the identity-bearing concept.

## Reused owners

```text
DomainCatalog / DomainNavigator       saved-project discovery and explicit loading
DomainModel / TransformController     class membership and rendered field schemas
BoundedInstanceSelection              class, random sample and explicit add/remove of
                                      exact instances, held once by the Discovery
                                      workbench and read by every source tab
BatchExecutor                         adaptive complete QID batches
SwingQuerySession                     execution, cancellation and structured query logs
WikidataSparqlClient                  SPARQL transport pointed at the Commons endpoint
WikimediaEntityLookup                 batched labels for QID/PID scene values
CommonsMedia                          filename, image URL and SVG handling
SearchableView / MultiView            every selection and result representation
GroupMembersView                      selected/co-depicted/qualifier/coverage facets
```

The Commons-specific layer owns only its endpoint query and the mapping from Commons RDF
rows to the source-neutral ObjectView values. ObjectView contains no Commons exception.
