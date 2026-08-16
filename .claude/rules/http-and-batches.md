---
description: One polite outbound HTTP path, and what a failed batch is allowed to conclude
paths:
  - "app/src/main/java/wikidata/api/**/*.java"
  - "app/src/main/java/wikidata/*Client.java"
  - "app/src/main/java/batch/**/*.java"
  - "app/src/main/java/**/extract/**/*.java"
  - "objectview/src/main/java/objectview/utils/UrlOpener.java"
---

# Outbound HTTP

**There is ONE polite HTTP path: `objectview.utils.UrlOpener`.** It handles 429 with
`Retry-After` and backoff, transient 5xx, cross-protocol http→https redirects, a contact
User-Agent and self-throttling. Raw `HttpClient` / `HttpURLConnection` / `URL.openStream` is
missing all of it — that is how a throttled response returned an error body that parsed to
empty, and a flag looked absent while the property was present.

```java
try (var in = UrlOpener.open(uri.toURL())) {
    return new String(in.readAllBytes(), UTF_8);
}
```

**When a need appears, improve UrlOpener — do not add per-site handling.**

Three clients are legitimately specialized and keep their own transport (`WikidataSparqlClient`
for WDQS concurrency and async, `WikidataApiClient` for the action API,
`WikiProjectMediaWikiClient`), plus `OpenAIMotivationTopicExtractor` for a different service.
They duplicate UA/retry/throttle; the end state is that they build on a shared transport. Do
not add a fourth.

# Batch failures

A batched load classifies each failure into what the executor should do next, and the
classification must reflect what actually happened:

- **The body ran out of time** (`ResponseTimeoutException`) → `TOO_HEAVY`: split the batch.
  Retrying the same oversized request unchanged only spends the backoff budget learning it
  again.
- **No response at all** (a connection timeout) → `UNAVAILABLE`: retry unchanged on the
  unavailable budget. There is nothing smaller to ask for yet.
- **Truncated 200 / EOF** → `TRANSIENT`: retry unchanged, then split.
- **429 / 5xx** → `UNAVAILABLE`, honouring `Retry-After` — the server said how long; don't
  second-guess it.
- **A status the server will not reconsider** (400, 404) → `FATAL`, and not worth five attempts.

**Never launder a timeout into an HTTP outcome.** A read timeout fires after the headers have
arrived, so the connection still answers 200; wrapping it with that status hands the classifier
a status it has no rule for, which is FATAL — and a batch that merely needed splitting refuses
the whole run. Establish the response boundary explicitly (read the status first), then a
timeout on either side of it means a different thing.

A run that could not load everything says so: report a partial result naming the affected QIDs
rather than letting an incomplete download pass for a complete one.
