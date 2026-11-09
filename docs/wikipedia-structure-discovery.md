# Wikipedia structure discovery

## Purpose

Wikipedia Discovery answers the question that comes before field configuration:

> What repeated, inspectable source structure exists across these selected articles,
> and what exact examples support it?

It is a read-only source workbench. It does not infer domain facts, add fields, edit the
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

1. Open one saved project through the shared domain chooser.
2. Choose a class whose loaded instances have English-Wikipedia correspondence.
3. Start with a random selection of at most 50 instances.
4. Add or remove instances through the ObjectView instance panels.
5. Either analyze the structured articles already retained in the snapshot or explicitly
   fetch exactly the selected articles and their infoboxes.
6. Inspect ObjectView result tabs for:
   - complete structured articles;
   - section headings with article coverage and exact section blocks;
   - infobox keys with observed values;
   - visible categories with contributing articles;
   - linked targets grouped by the section in which each link occurred;
   - block kinds: every block-level element kind in the fetched HTML, named by its tag
     and its own classes (`table.infobox`, `div.poem`), with what the article structure
     did with it — a block kind, folded into a surrounding block, read but its text not
     located, or not retained. The outcome is the one the section builder recorded, not a
     prediction. It needs freshly fetched HTML: a retained article keeps only the blocks
     its structure chose, so it cannot show what was dropped;
   - unavailable inputs and their exact failures.

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
