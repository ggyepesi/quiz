package wikipedia.discovery;

import quiz.transform.app.DomainCatalog;
import quiz.transform.ui.DomainNavigator;

/** Entry point for bounded, read-only comparison of Wikipedia source structure. */
public final class WikipediaDiscoveryApp {
    private WikipediaDiscoveryApp() {}

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::all,
                "Wikipedia discovery — saved projects",
                "Open in Wikipedia Discovery",
                WikipediaDiscoveryPanel::openFrame);
    }
}
