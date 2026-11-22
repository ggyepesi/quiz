package domain.datasource.discovery.app;

import domain.datasource.discovery.ui.DiscoveryWorkbench;
import domain.transform.app.DomainCatalog;
import domain.transform.ui.DomainNavigator;

/** Entry point for bounded source discovery over a saved project. Inspection is
 * read-only; an explicit promotion action may update ModelBuilder configuration. */
public final class DiscoveryApp {
    private DiscoveryApp() { }

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::all,
                "Discovery — saved projects",
                "Open in Discovery",
                DiscoveryWorkbench::openFrame);
    }
}
