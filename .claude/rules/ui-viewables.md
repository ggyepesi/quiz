---
description: How domain data is shown — one look and one set of affordances across every panel
paths:
  - "app/src/main/java/**/workbench/**/*.java"
  - "app/src/main/java/**/ui/**/*.java"
  - "app/src/main/java/**/swing/**/*.java"
  - "objectview/src/main/java/objectview/render/**/*.java"
  - "objectview/src/main/java/objectview/table/**/*.java"
  - "objectview/src/main/java/objectview/view/**/*.java"
---

# Showing domain data

**Wherever a panel lists domain data, model the rows as `Viewable`s and render them through the
shared machinery — never a hand-built `JTable`/`JList` for that one screen.**

Every bespoke table re-invents what the shared path already has: search, field configuration,
sorting, links, selection, virtualization, copy. Each one then drifts, so the same data feels
like a different application depending on which panel you opened. The trigger case: Property
Discovery's results had no search box, because they were a `JTable` rather than Viewables.

## How

- Give the row type a small `Viewable` (`WikidataPropertyViewable`,
  `DiscoveredPropertyViewable`), then render through `objectview.view.SearchableView`:

  ```java
  SearchableView.builder(rows)
          .type(RowViewable.class)
          .mode(RenderingMode.TABLE)              // or CARD; the user can switch
          .valueLinker(WikidataLinks.valueLinker())
          .selectionListener(selected -> …)
          .hiddenFields(columnsNothingCanFill)
          .build();
  ```

  `CachedPropertyViewablePanel` and `PropertyDiscoveryPanel` are the worked examples.

- **Per-row buttons are what a bespoke table was bought for.** Use global buttons acting on the
  selected row instead: fewer widgets, and the actions stay legible as the row set grows.

- **What a field IS decides its column, and the declared Java type decides its kind.** A media
  value is a `MediaValue` field, text is a `String` field; one `Object` column that is an image
  on some rows and text on others classifies as UNKNOWN and loses search, sort and config.

- **Anything the row merely carries is `@Hidden`.** Every reflectively reachable field is view
  data, so a stored source record renders as one cell repeating the whole row.

- **A column no row can fill is not shown** — pass it in `hiddenFields`. A column standing empty
  down every row reads as something missing.

- **Render links wherever feasible.** QIDs, PIDs and URLs are clickable everywhere; supply
  `WikidataLinks.valueLinker()` rather than adding a second, near-duplicate URL column.

- **Keep the painted-row rendering** (drag-to-select text blocks, virtualization for large
  sets). This rule is about WHAT is rendered; that is about HOW. Don't change the card look.

## objectview stays generic

`objectview` must know nothing about Wikidata or any app concept. App knowledge enters through
annotations and contracts it already understands (`@Link`, `@Hidden`, `MediaValue`,
`RenderContext.valueLinker`). A change that teaches one layout something new must teach every
layout the same thing, or a card and a table will disagree about the same value.
