# DataSourceApp

## Purpose

`quiz.datasource.app.DataSourceApp` is the interactive home for reading external sources into a
loaded project. It resolves identities, loads configured field values, inspects source data and
reviews proposals before applying them. It does not define a second acquisition pipeline.

The application boundary is:

| Application | Owns |
|---|---|
| ModelBuilder | datasource and field configuration |
| DiscoveryApp | bounded source-structure inspection and explicit promotion into ModelBuilder configuration |
| DataSourceApp | external identity/field acquisition, review and application |
| TransformApp | local grouping, transformations and manual field edits |

## Shared scope and processing

DataSourceApp and TransformApp currently obtain the same class, named-selection, group, field and
Present/Missing/All scope by opening the same purpose-parameterized workbench panel. This is one
reused implementation, not yet the separate shared scope component tracked by #536. They also
render the same instances through ObjectView. Only their action sets differ.

DataSourceApp invokes the existing identity resolution, Find Data, Wikipedia evidence and
discovery processes. TransformApp invokes the existing local group, relation-analysis and
manual-edit operations. The applications do not launch or automate one another, and no source
reader, review model or apply path is duplicated.

## First workflow

1. Run `quiz.datasource.app.DataSourceApp` from the `app` module.
2. Load a saved model or domain through the shared project chooser.
3. Select a class, optional named selection or group, and a field.
4. Choose All, Present or Missing to state the exact instance scope.
5. Press **Load field…** to inspect the configured sources and start the explicit load.
6. Review and apply proposals through the existing process result UI.
7. Use **Save model/domain** to persist the applied values and project state.

Identity resolution and both discovery actions are also scoped to the currently shown
instances. Wikidata and Wikipedia discovery open the same Discovery workbench used by
DiscoveryApp, with the same Add selection, Remove selection, selected-count and random-sample
controls. Merely selecting a field, group or instance never starts a request or changes the
project.
