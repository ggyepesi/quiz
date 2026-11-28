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
  The text field's **Read full text…** menu opens its exact value in a selectable, scrollable
  reader without changing the card. In each owning window, the same instance and field opens
  one reader, titled with the instance and field. Linked passages keep their authored links;
  hovering a link still names its destination, not a different action.
  An accepted passage may show its configured reference field's unambiguous names as
  instance links, without changing the stored text or creating references. Such a link uses
  ordinary card navigation. Following a reader link puts the reader aside without closing
  it. Back raises it at its previous scroll position; if it was closed, Back resolves the
  original card by identity.
  A quiz can opt out of instance links in its prompt or answer text, independently.
  The shared renderer keeps the words and external URLs; the choice does not change quiz
  keys, domain data, or any other view.

- **Large inline collections stay on the shared virtualized path too.** Expanding one parent
  must not create one UI component per member. Live appends update only the new suffix and the
  containing field's count; they do not rescan or relayout all prior members.

- **Render `ViewConfig` literally and recursively.** Unticked fields contribute nothing. A
  selected object with no selected children contributes its field name. A selected DISPLAY
  field has one painted occurrence: the object's caption, carrying that DISPLAY field's path
  and value for search/highlighting; it is never repeated as a body row. Every selected
  collection contributes `field name (size)` immediately and builds its configured members only
  after expansion. View Config's **Show empty fields** is on by default; turning it off suppresses
  selected blank text and collections/maps with no present member through this same shared decision path,
  without suppressing a non-null reference. Initial expansion is independent presentation policy, one rule for every
  layout (`Disclosure.initiallyOpen`, #398): each expand opens exactly one level — nested
  objects and collections start folded under their caption or field name, a caption-less
  collection member shows itself, singleton media stays open. A view may name paths that
  open with their owner (`RenderContext.setOpensWithOwner`, e.g. the query log's `steps`);
  that is the rule's input, not an exception in it, and no renderer decides it alone.
  A root card is that rule's root occurrence, in the same choice store; whether roots start
  open or as captions only is the view config's `rootsFolded`, set by View Config's
  **Top-level cards: captions only** (#407). More than one level at once is the explicit
  **Expand all below** / **Collapse all below** on one node.
  A scalar reference to an object already shown as a top-level card is navigation only.
  Members of an explicitly expanded object collection remain that collection's configured
  projection: top-level registration must not suppress their selected child fields. A
  reference member whose target has a top-level card keeps the projection and navigates from
  its caption; the disclosure glyph independently expands or collapses that projection.
  `@Inline`, `@Reference`, dynamic/reflected backing, and card/table layout are presentation
  choices and must not alter those semantics.

## objectview stays generic

`objectview` must know nothing about Wikidata or any app concept. App knowledge enters through
annotations and contracts it already understands (`@Link`, `@Hidden`, `MediaValue`,
`RenderContext.valueLinker`). A change that teaches one layout something new must teach every
layout the same thing, or a card and a table will disagree about the same value.

An ObjectView group may declare that its structural children start expanded. Group-tree
rendering honors that declaration everywhere; ordinary authored and facet groups remain folded.
Within one sibling set, independently produced leaf groups are outlined below their unique
smallest strict member superset. Authored nesting remains unchanged; empty, equal and ambiguous
member sets remain siblings.
The group outline searches and optionally orders captions only; heterogeneous group classes do
not create a synthetic shared field configuration. The selected group's parallel member view is
still an ordinary `SearchableView` and owns the full Search/Sort/View controls for those members.

The instances viewer opts into ObjectView's shared tab layout with four panel slots per
tab by default. A group takes two slots, keeping its outline and member pane side by side
with their divider controls. Other multi-section hosts stay side by side unless they opt in.
A reference to a loaded instance stays clickable even when its class is unchecked.
Following it checks that class in **Classes shown**, reveals its tab, and focuses the card
through the existing navigation registry (#584). Hiding a class still excludes it from the
shared search until it is shown again. Instance tabs with search matches get a marker from
their existing search engines, without re-running the search or changing tabs. Clearing
the search clears the markers. The shared Back action restores the originating tab and
scroll position; manual tab changes and search-hit navigation do not create a Back entry.

The shared **Show empty fields** control applies to every card view under the same
multi-instance host, including unchecked classes, nested tabs, group details and
member cards. All use the same field-presence rule.
