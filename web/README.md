# quiz web

SvelteKit frontend for the Viewable JSON API. It renders any `Viewable` as a
card from `/api/viewable/...`, with references shown as chips that expand in
place (fetching the child on demand) — the web counterpart of the desktop
`ViewablePanel`.

## Run

1. Start the Java API (from the repo root):

   ```
   mvn -o exec:java -Dexec.mainClass=quiz.web.ViewableServerMain
   ```

   It serves on `http://localhost:7070` with CORS enabled. The first request
   for a type triggers its load (Oscar = a one-time Wikidata fetch).

2. Start the frontend:

   ```
   cd web
   npm install
   npm run dev
   ```

   Open the printed URL (default `http://localhost:5173`).

To point at a different API base, set `VITE_API` (e.g. in `web/.env`):

```
VITE_API=http://localhost:7070
```

## How it maps to the API

| field `kind` | rendered as                                   |
|--------------|-----------------------------------------------|
| `text`       | inline value                                  |
| `list`       | comma-joined scalars                          |
| `link`       | anchor (`@Link`, opens in a new tab)          |
| `ref`/`refs` | collapsible chip(s); expand fetches the child |
| `inline`     | nested card(s) shown expanded (`@ViewableInline`) |

## Structure

```
src/lib/api.js            fetch helpers
src/lib/ViewableCard.svelte  renders a ViewableView (recursive for inline)
src/lib/ViewableChip.svelte  a reference chip with lazy expand/collapse
src/routes/+page.svelte   type tabs + searchable list + selected card
```
