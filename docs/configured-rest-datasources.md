# Configuration-defined bounded REST datasources

## Purpose

A simple JSON REST source should not require a provider class, client, discovery query and
result panel written again for that one site. A checked-in `*.datasource.json` file under
`data/datasources/` now installs an ordinary `DatasourceProvider` whose related-record
operations are exposed by the shared registry.

The first real configuration is
`data/datasources/met-collection.datasource.json`. It asks the Metropolitan Museum of Art
Collection API which Egyptian Art objects are tagged with exact selected Wikidata entities.
It is deliberately a discovery operation: it shows source-native `MuseumArtifact` records and
the selected domain instances they match, but it does not add a class, field or instance to the
project.

## One execution path

```text
*.datasource.json
        ↓ load and validate
ConfiguredRestDatasourceProvider
        ↓ ordinary registry capability
RelatedRecordDiscoveryOperation
        ↓ exact selected entity seeds
bounded search requests
        ↓ capped, deduplicated record IDs
bounded detail requests
        ↓ configured evidence test
DiscoveredRelatedRecord
        ↓
ObjectView connected-object result
```

The operation uses `UrlOpener`, `BatchExecutor`, `SwingQuerySession` and the structured query
log. It does not own another HTTP, retry, cancellation or result-rendering path. Search and
detail requests run with the configured bounded concurrency. One unavailable request makes
the result `PARTIAL` and names the failed work; records already read remain visible. A
server-side status (429, 5xx) is retried on the batch budget first. A status the server will
not reconsider (another 4xx) is not retried and costs only that one request, whether it
answered a seed's search or a detail record.

## Supported grammar

This is intentionally not a general query language. One operation declares:

- its input identifier namespace;
- the maximum selected seeds, result IDs per seed and concurrent requests;
- one HTTP(S) search URL and fixed/template query parameters;
- the JSON path containing returned record IDs and, optionally, the source's total count;
- one HTTP(S) detail URL containing `${result.id}`;
- one detail-record JSON path whose values prove the relation to a seed;
- one record identity path, display path and ordered field list.

Search query values may contain `${seed.id}`, `${seed.label}` and `${limit}`. The detail URL
must contain `${result.id}`. Values are URL encoded by the shared configured-operation code;
configuration contains no pre-encoded user value.

JSON field paths are dot-separated field names. Appending `[]` projects every member of an
array, for example:

```text
objectIDs[]
tags[].Wikidata_URL
tags[].term
```

Configured record fields currently support text, language text, dates, quantities, links and
media. Entity references are deliberately not accepted yet: a truthful reference needs an
explicit target namespace and identity mapping, which this first grammar does not define.

The grammar has no arbitrary expressions, scripts, unbounded paging, link crawl, authentication
or mutation instruction. A new need extends this one grammar only when a second real source
forces it.

## Met operation

For each selected instance, the checked-in operation issues:

```text
GET /public/collection/v1.1/search
    ?departmentId=10
    &hasImages=true
    &tags=true
    &limit=20
    &q=<selected display label>
```

The label search only finds bounded candidates. A detail record is retained only when one of
its `tags[].Wikidata_URL` values names the selected entity's exact QID. Thus a title that merely
contains “Anubis” does not relate an object to Anubis; a Met subject tag carrying
`https://www.wikidata.org/wiki/Q47534` does.

At most 10 selected instances enter one run and at most 20 record IDs are retained per seed.
Duplicate IDs across seeds are fetched once. The result shows image, linked Met source, object
type, subject tags, date/period/dynasty/reign, culture, medium, dimensions, geography and any
object Wikidata source exposed by the detail record.

## Adding another configured source

Add another `*.datasource.json` file under `data/datasources/` (beside `data/wikidata/`, from
`aux.Constants.dataDirectory`) and restart the application. If its operation uses the existing
bounded search/detail grammar, it appears as another Discovery workbench tab without Java
changes.

The directory is read once per process, because every model validation, snapshot graph and
generation composes the registry. An invalid file, or a missing directory, is never silently
ignored and never stops anything else: that provider is not installed, and the Discovery
workbench shows a **Configured datasources — not installed** tab naming the exact path and
why. Code-backed sources and the model editors are unaffected.

If the source cannot honestly fit this grammar—for example it needs authentication, continuation
tokens, POST bodies, a graph expansion or a different evidence relation—do not imitate it with
misnamed fields. Raise that missing capability and extend the shared construct with forcing tests.
