package commons.discovery;

import quiz.transform.app.DomainCatalog;
import quiz.transform.ui.DomainNavigator;

/** Entry point for bounded Wikimedia Commons structured-data discovery. */
public final class CommonsDiscoveryApp {
    private CommonsDiscoveryApp() { }

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::all,
                "Commons discovery — saved projects",
                "Open in Commons Discovery",
                CommonsDiscoveryPanel::openFrame);
    }
}
