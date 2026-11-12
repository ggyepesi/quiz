# Wikimedia Commons structured-data discovery

## Purpose

Commons Discovery is the read-only source workbench for answering:

> Which Wikimedia Commons files explicitly depict these exact modeled entities, and
> which structured metadata do those files actually carry?

It is deliberately a separate application while this datasource is being understood. It
does not add fields, alter instances, save groups, or infer that visually similar media
depicts an entity. A later source recipe can be designed from observed coverage without
turning discovery into a second extraction path.

## First bounded workflow

1. Run `commons.discovery.CommonsDiscoveryApp` from the `app` module.
2. Open a saved generated project through the shared saved-project navigator.
3. Choose a class whose loaded instances have Wikidata QIDs.
4. Start a random sample, then add or remove instances in the two ObjectView selection
   panels. One run accepts at most 20 exact QIDs.
5. Choose the visible maximum number of Commons files per QID (20 by default).
6. Press **Fetch N selected entities**. The action states the exact QID count, the per-QID
   cap, the maximum file count before duplicates are merged, and that the project is not
   changed.
7. Inspect:
   - **Connected objects**, a shared ObjectView `MultiView` containing the original domain
     instances and the resulting media objects. `MediaRepresentation.depictedEntities`
     refers to those same instances, so a reference navigates to its original card;
   - **Groups**, with read-only facets by depicted entity (named by label and QID, since
     instances may share a label) and by metadata presence;
   - **No structured depiction**, containing exact selected instances for which the bounded
     query returned no file.

Each media object exposes its image, linked Commons file page, English caption (falling
back to the filename), exact depicted instances, QID-valued creators, creation dates,
medium, collection, and location/institution when Structured Data on Commons provides
them. Missing values remain missing; discovery does not manufacture an entity from a
blank node or label.

## Acquisition and bounds

The query uses the programmatic QLever Wikimedia Commons endpoint recommended by the
Commons query-service documentation. One request contains one ordered, capped subquery
per exact selected QID:

```text
selected QID -> Commons file whose depicts (P180) equals that QID -> structured metadata
```

The shared batch executor owns this QID batch. If the endpoint cannot complete it, the
executor splits the QID list and retries smaller complete batches. It never widens the
input, follows an inverse relation, crawls another depicted entity, or silently raises a
cap. Query logs show every issued SPARQL request and its elapsed time; running work has an
explicit cancel action and closing the window asks before cancellation.

Only image files are shown as depictions. Audio, video and documents also carry depicts
statements but would render as a broken image; they still count toward the per-QID cap, and
the result states how many were not shown.

Duplicate files found for several selected QIDs become one `MediaRepresentation` with
several depicted-entity references. This keeps the Commons file, rather than a
file/subject pair, as the identity-bearing concept.

## Reused owners

```text
DomainCatalog / DomainNavigator       saved-project discovery and explicit loading
DomainModel / TransformController     class membership and rendered field schemas
BoundedInstanceSelection              class, random sample and explicit add/remove of
                                      exact instances, shared with Wikipedia Discovery
BatchExecutor                         adaptive complete QID batches
SwingQuerySession                     execution, cancellation and structured query logs
WikidataSparqlClient                  SPARQL transport pointed at the Commons endpoint
WikimediaEntityLookup                 batched labels for QID-valued metadata
CommonsMedia                          filename, image URL and SVG handling
SearchableView / MultiView            every selection and result representation
GroupMembersView                      depicted-entity and field-coverage facets
```

The Commons-specific layer owns only its endpoint query and the mapping from Commons RDF
rows to the source-neutral ObjectView values. ObjectView contains no Commons exception.
