package domain.transform.ui;

/**
 * Which application a shared workbench panel serves. DataSourceApp and TransformApp use the
 * same class/selection/group/field scope and rendering; the purpose decides only which
 * explicit actions are offered, never a second execution path (#536).
 */
public enum WorkbenchPurpose {
    /** TransformApp: local grouping, transformations and manual field edits. */
    TRANSFORM,
    /** DataSourceApp: external identity and field acquisition, review and application. */
    DATASOURCE;

    public boolean showsTransformActions() {
        return this == TRANSFORM;
    }

    public boolean showsDatasourceActions() {
        return this == DATASOURCE;
    }
}
