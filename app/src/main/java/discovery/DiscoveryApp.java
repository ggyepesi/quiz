package discovery;

import quiz.transform.app.DomainCatalog;
import quiz.transform.ui.DomainNavigator;

/** Entry point for bounded, read-only source discovery over a saved project. */
public final class DiscoveryApp {
    private DiscoveryApp() { }

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::all,
                "Discovery — saved projects",
                "Open in Discovery",
                DiscoveryWorkbench::openFrame);
    }
}
