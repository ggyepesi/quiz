# Discovery workbench

`domain.datasource.discovery.app.DiscoveryApp` opens one bounded workbench over a saved project. It answers
"what could this source add to these instances?" before anything is configured.

## One selection, one source/action/result navigator

The instances are selected once, on the left, through the shared
`BoundedInstanceSelection`: a class, a random starting sample, and ObjectView panels to
add and remove exact instances. It admits instances with a Wikidata QID, the identity
every source starts from.

The remaining area uses the shared `WorkbenchActionResultNavigator`:

```
selection | source/action/result tree | selected action or result view
```

The tree is workflow navigation, not a `ViewableGroup` tree: its nodes do not claim domain
membership. Selecting a source, action or result only reveals it. The explicit verb-labelled
button in the action panel is the only thing that starts work. When the action completes, its
existing `JTabbedPane` remains the result-view deck and selection model, but its horizontal tab
strip is hidden. The navigator mirrors those exact view titles as result children and is the one
visible result navigator. A new run replaces that action's result children; query logs retain run
history.

Each action reads exactly the shared selection:

| Source / action | Reads | Per-run bound |
|---|---|---|
| Wikipedia articles | retained or fetched article structure and infobox (`docs/wikipedia-structure-discovery.md`) | 50 articles |
| Commons depictions | Structured Data on Commons depiction scenes (`docs/commons-structured-data-discovery.md`) | 20 QIDs |
| Wikipedia pageviews | English Wikipedia views by human readers over complete months | 50 articles |
| Metropolitan Museum artifacts | Met Egyptian Art records whose subject tag names the selected exact Wikidata entity (`docs/configured-rest-datasources.md`) | 10 entities, 20 candidate IDs each |
| Wikidata statements and qualifiers | every property, statement value and qualifier for the selected QIDs | 50 instances, queried in batches of 10 QIDs |

A source that needs more than a QID (an English Wikipedia article) counts the selected
instances it cannot read and says so; it never narrows the selection for the others. A
selection larger than a source's bound disables that source's run and names how many to
remove. While any source runs, the selection is locked; one source finishing does not
unlock it under another that is still running.

Moving between actions never asks for the instances again and inspecting results never changes
the project. An explicit promotion offered by a source action may add its reusable finding to
the working ModelBuilder configuration; its separate Save action states the project file that
will be written.
DataSourceApp embeds this same source/action/result tree below its existing instance scope; it
does not show datasource launcher buttons or open a second selection window. Its selected
instances span the upper work area. The lower area uses the navigator's ordinary tree/detail
layout, so the selected action or result receives the full right-hand width instead of becoming a
fourth narrow column. The datasource roots and their actions are visible before any instance is
selected. With no explicit card or modeled-group selection, an action reads every currently shown
eligible instance. Selecting cards or modeled groups narrows that input immediately. Only the
explicit Run control applies the action's bound and may therefore be disabled; navigating the
datasource and action tree never is.

The Wikipedia source also contains the current field's configured evidence action. For a modeled
group it automatically targets the declared members field. Thus selecting **Assessors of Maat**
and then **Wikipedia → Find configured field evidence (including table rows)** reveals the ordinary
**Find table-row instances…** action: its current members remain, and every row proposes its
configured row instance, even when its display name has no Wikipedia link. A display-cell link to
a loaded instance fills the row's configured reference field rather than replacing the row. This
delegates to the
same `ValidationPanel` acquisition and review path used everywhere else.

The Wikipedia, Commons, Pageviews and Wikidata actions are code-backed source workflows. Further
bounded related-record actions are discovered from the datasource registry and grouped under
their provider source node. Their result records, source fields and limits come from checked-in
datasource configuration; the workbench does not name or branch on Met.

## Reuse by other workbenches

`WorkbenchActionResultNavigator` owns only stable source/action/result identities, navigation,
the detail-card host and tree ↔ tab synchronization. A host supplies its action component and
publishes the exact result tabs that component already produced. The navigator does not own
selection, execution, fetching, persistence, result construction or domain grouping. This is
the seam Configuration, Transform and other action/result-heavy workbenches can reuse without
depending on DataSourceApp.

## Pageviews

The action reads, per selected article, monthly views by human readers (`agent=user`) over
the chosen number of complete months ending last month, summed. The article title is the
one retained Wikidata sitelink correspondence. The Pageviews service does not follow
redirects and answers 404 when it holds nothing for a title in the window; the row then
has no value, never zero. Rows are ordered most read first, so it shows whether the
measure separates well-known from obscure instances before anyone binds it to a field.

The same measurement is a field source: choose **From: Datasource operation** and
**Wikimedia Pageviews — English Wikipedia article views** on a number field. Generation
reads it after extraction through the same `PageviewsClient`. The window is the recipe's
`months` parameter; the value changes only when the domain is generated again.

## Adding a source

A field source is a `DatasourceProvider` registered in `Datasources.standard()` plus the
`ExternalSourceFamily` that acquires its prepared operations
(`StandardExternalSourceFamilies.create()`). Its operations and their
`ParameterDescriptor`s are offered by the field editor's **Datasource operation** choice
without a new `FieldSourceType` constant; the stored binding is the field's whole
configuration. A discovery view for it is one more action registered under its source and
reading the workbench's shared selection.

A bounded JSON REST related-record source may instead be installed entirely through
`data/datasources/*.datasource.json`. It compiles into the same registry as a code-backed
provider, executes through the shared batch/query-log path and renders through the generic
related-record ObjectView result tabs. See `docs/configured-rest-datasources.md`.
