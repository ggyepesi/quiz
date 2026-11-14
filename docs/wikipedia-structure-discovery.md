# Wikipedia structure discovery

## Purpose

Wikipedia Discovery answers the question that comes before field configuration:

> What repeated, inspectable source structure exists across these selected articles,
> and what exact examples support it?

It is the read-only **Wikipedia articles** tab of the Discovery workbench
(`docs/discovery-workbench.md`). It does not infer domain facts, add fields, edit the
model, or create instances. A promising result may later be promoted explicitly to an
ordinary datasource recipe; the discovery application is not a second extraction path.

## Reused owners

```text
DomainCatalog / DomainNavigator       saved projects and explicit loading
DomainModel / TransformController     classes, membership and rendered schema
WikipediaArticleFetchQuery            bounded structured-article acquisition
WikipediaArticleClient                sections, blocks, links and categories
WikipediaInfoboxClient                native parameters and rendered rows
SwingQuerySession                     background execution, cancellation and query log
SearchableView                        every instance and discovery-result view
```

The retained `WikipediaArticle` remains the one source-evidence artifact. Discovery reads
it; it does not fetch or store another interpretation of the article. A fetch makes one
`action=parse` request per article, and the structured article and its infobox are both
read from that response, so they always describe the same revision (#461).

## First bounded workflow

1. Run `discovery.DiscoveryApp` and open one saved project through the shared domain
   chooser, or press TransformApp's Wikipedia discovery action, which hands over its
   selected instances and opens this tab.
2. Choose a class whose loaded instances have Wikidata QIDs.
3. Start with a random selection of at most 50 instances.
4. Add or remove instances through the workbench's ObjectView instance panels. Selected
   instances without an English Wikipedia article correspondence are counted and not
   read.
5. Either analyze the structured articles already retained in the snapshot or explicitly
   fetch exactly the selected articles and their infoboxes.
6. Inspect ObjectView result tabs for:
   - a visible **Structural groups** browser, using the same group-tree plus ObjectView
     member panel as TransformApp. Its section, infobox, category, link, table-header and
     HTML-element groups are read-only views of the exact selected domain instances. The
     section branch mirrors every retained structure the generic article reader recognizes:
     sections contain their paragraphs and named definitions, lists contain their items and
     nested lists, and tables contain their rows (with cells rendered as row fields). Thus
     the Heracles article appears as `Mythology → Labours of Heracles → Ordered list — 12
     items (Heracles) → …`; no mythology-specific rule creates that hierarchy. Section paths
     are shared across the selected articles, while each list or table node names its one
     article, so two articles' lists never merge. Nested items sit under the item whose list
     holds them, as the retained hierarchy states;
   - complete structured articles;
   - section headings with article coverage, full parent-to-child paths and exact section
     blocks. A subsection such as an individual Labour of Heracles is captioned with its
     complete path instead of looking like an unrelated top-level section;
   - every retained section-content block with its article, full section path, source
     element, text and links. When Wikipedia expresses a named item as a definition term
     followed by one or more descriptions, the term is retained as that item's title and
     the complete term-and-description text remains its exact evidence;
   - list items in their ordered/unordered, nested source hierarchy. The searchable list-item
     projection uses concise authored text as its caption, while `Item 1` remains visible as
     its source position; it keeps the article and section path and refers to the same
     retained item. Anonymous paragraphs and table rows follow the same rule;
   - table rows and their header/data cells, including captions, links, row/column spans and
     exact source ranges. The searchable table-row projection reads the retained table and
     never reconstructs cells from flattened table text;
   - table-header values with coverage and exact examples;
   - infobox keys with observed values;
   - visible categories with contributing articles;
   - linked targets grouped by the section in which each link occurred;
   - HTML elements: every structural element kind in the fetched HTML, named by its tag
     and its own classes (`table.infobox`, `div.poem`), with what the article structure
     did with it — a block kind, a section heading, folded into a surrounding block, empty, or
     not retained. The outcome is the one the section builder recorded, not a
     prediction. It needs freshly fetched HTML: a retained article keeps only the blocks
     its structure chose, so it cannot show what was dropped;
   - unavailable inputs and their exact failures.

Selecting one section, infobox key, category, link, table-header value or HTML-element
outcome selects its matching node in the **Structural groups** tab; opening that tab shows
the original domain instances through the shared ObjectView member panel. This is an ephemeral
structural-presence group: it states only that those retained articles contain the selected
source structure. It neither interprets the values nor creates or saves a TransformApp group.

Coverage always uses the explicit selected article count as its denominator. Failed or
missing articles remain visible rather than silently shrinking the population.

## Boundary

- No article-link crawl.
- No identity by label.
- No automatic Wikidata correspondence for linked pages.
- No automatic field meaning from a heading, category, infobox key or link.
- No model mutation while inspecting or selecting.
- No source-specific result renderer: all rows are Viewables rendered by ObjectView.

For example, `Possible animal model` can reveal a Bennu → Bennu heron candidate together
with its exact paragraph. Discovery reports that structure and its coverage. A later
configured recipe would decide whether the relation means `possibleAnimalModel`; discovery
does not call it `associatedAnimal` or `symbolizedBy` on its own.
