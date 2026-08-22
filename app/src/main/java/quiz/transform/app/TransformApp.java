package quiz.transform.app;

import quiz.transform.ui.DomainNavigator;

/**
 * Entry point for the transform workbench: opens the domain navigator wired with the wikidata
 * {@link DomainCatalog} and {@link DomainSaver}.
 *
 * <p>This used to claim that the UI package has no wikidata dependency and that this bridge
 * supplies it. That stopped being true a long time ago — {@code quiz.transform.ui} names
 * wikidata types thirty times across seven of its packages, for field-source mappings, the
 * discovery pickers and the enrichment providers it hosts. What the bridge actually supplies is
 * the CATALOG: which datasets exist and how one is opened and saved. Everything the workbench
 * knows about Wikidata beyond that, it knows directly.
 */
public final class TransformApp {

    private TransformApp() {}

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::all, new DomainSaver());
    }
}
