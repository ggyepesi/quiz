package quiz.transform.app;

import quiz.transform.ui.DomainNavigator;

/**
 * Entry point for the transform workbench: opens the domain navigator wired with
 * the wikidata {@link DomainCatalog} and {@link DomainSaver}. The UI package itself
 * has no wikidata dependency; this bridge supplies it.
 */
public final class TransformApp {

    private TransformApp() {}

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::all, new DomainSaver());
    }
}
