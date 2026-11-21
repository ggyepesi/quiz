package datasourceapp;

import quiz.transform.app.DomainCatalog;
import quiz.transform.app.DomainSaver;
import quiz.transform.ui.DomainNavigator;
import quiz.transform.ui.TransformWorkbenchPanel;

/**
 * Entry point for source-driven acquisition and review over a saved project.
 *
 * <p>The application shares the class/group/field scope, rendering, and acquisition
 * processes with the existing workbenches. Its UI exposes only actions that read external
 * data sources; local grouping and value edits remain in TransformApp.
 */
public final class DataSourceApp {

    private DataSourceApp() { }

    public static void main(String[] args) {
        DomainSaver saver = new DomainSaver();
        DomainNavigator.show(
                DomainCatalog::all,
                "Data sources — saved projects",
                "Open in DataSource Workbench",
                (domain, name) -> TransformWorkbenchPanel.openDatasourceFrame(
                        domain, name, saver));
    }
}
