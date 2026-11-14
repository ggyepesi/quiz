# Discovery workbench

`discovery.DiscoveryApp` opens one read-only workbench over a saved project. It answers
"what could this source add to these instances?" before anything is configured.

## One selection, one tab per source

The instances are selected once, on the left, through the shared
`BoundedInstanceSelection`: a class, a random starting sample, and ObjectView panels to
add and remove exact instances. It admits instances with a Wikidata QID, the identity
every source starts from. Each source is a tab on the right that reads exactly that
selection:

| Tab | Reads | Per-run bound |
|---|---|---|
| Wikipedia articles | retained or fetched article structure and infobox (`docs/wikipedia-structure-discovery.md`) | 50 articles |
| Commons depictions | Structured Data on Commons depiction scenes (`docs/commons-structured-data-discovery.md`) | 20 QIDs |
| Wikipedia pageviews | English Wikipedia views by human readers over complete months | 50 articles |

A source that needs more than a QID (an English Wikipedia article) counts the selected
instances it cannot read and says so; it never narrows the selection for the others. A
selection larger than a source's bound disables that source's run and names how many to
remove. While any source runs, the selection is locked; one source finishing does not
unlock it under another that is still running.

Moving between tabs never asks for the instances again, and no tab changes the project.
TransformApp's Wikipedia discovery action opens this same workbench with its selected
instances handed over, on the Wikipedia tab.

## Pageviews

The tab reads, per selected article, monthly views by human readers (`agent=user`) over
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
configuration. A discovery view for it is one more tab registered on the workbench's
shared selection.
