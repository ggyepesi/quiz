package discovery;

import domain.DomainModel;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wikipedia and Commons discovery were separate applications, each selecting its own
 * instances: moving between sources meant choosing the same instances again. One
 * workbench now holds the selection and every source tab reads it, counting the selected
 * instances it cannot read rather than refusing them for everyone.
 */
class DiscoveryWorkbenchTest {

    @Test void aSelectionMadeOnceIsWhatEverySourceReads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            WikidataDynamicObject withoutArticle = deity("Q2831", "Ra", "");
            DiscoveryWorkbench workbench = workbench(domain(maat,
                    withoutArticle), new DiscoveryWorkbench.InitialSelection(
                    "EgyptianDeity", List.of(maat, withoutArticle), "a test"));
            try {
                assertEquals(2, workbench.selection().size());
                assertEquals("Fetch 1 selected article",
                        field(workbench.wikipedia(), "fetch", JButton.class).getText(),
                        "the Wikipedia tab reads only instances with an article");
                assertEquals("Fetch 2 selected entities",
                        field(workbench.commons(), "fetch", JButton.class).getText(),
                        "Commons reads every selected QID");
                assertEquals("Read views of 1 selected article",
                        field(workbench.pageviews(), "fetch", JButton.class).getText());
                assertEquals("Fetch statements and qualifiers for 2 selected instances",
                        field(workbench.wikidata(), "fetch", JButton.class).getText(),
                        "Wikidata inspection reads the same shared selection");
                assertEquals(1, workbench.relatedRecords().size());
                JButton met = field(workbench.relatedRecords().getFirst(),
                        "fetch", JButton.class);
                assertEquals("Fetch MuseumArtifact records for 2 selected instances",
                        met.getText());
                assertTrue(met.getToolTipText().contains("at most 20 search result(s)"));
                assertTrue(met.getToolTipText().contains("project is not changed"));
            } finally {
                workbench.close();
            }
        });
    }

    @Test void aContextualWorkbenchUsesTheExactVisibleScopeAndStillOffersAddSelection()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            WikidataDynamicObject ra = deity("Q2831", "Ra", "Ra");
            WikidataDynamicObject anubis = deity("Q47534", "Anubis", "Anubis");
            DiscoveryWorkbench workbench = workbench(domain(maat, ra, anubis),
                    new DiscoveryWorkbench.InitialSelection(
                            "EgyptianDeity", List.of(maat, ra), List.of(),
                            "DataSourceApp — symbol missing"));
            try {
                assertEquals(0, workbench.selection().size());
                assertEquals(2, ((List<?>) field(workbench.selection(), "classMembers",
                        List.class)).size(),
                        "the available cards are exactly the calling workbench's scope");
                assertEquals("Selected entities — 0",
                        workbench.selection().tabs().getTitleAt(1),
                        "the shared selection reports its exact count before sampling");
                assertEquals("Add selection",
                        field(workbench.selection(), "addSelection", JButton.class).getText(),
                        "manual Add selection is present without starting a sample");
            } finally {
                workbench.close();
            }
        });
    }

    @Test void oneSourceFinishingDoesNotReleaseTheSelectionUnderAnotherThatRuns()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            DiscoveryWorkbench workbench = workbench(domain(maat), null);
            try {
                JComboBox<?> type = field(workbench.selection(), "type", JComboBox.class);
                Object wikipedia = new Object();
                Object commons = new Object();
                workbench.selection().setRunning(wikipedia, true);
                workbench.selection().setRunning(commons, true);
                workbench.selection().setRunning(wikipedia, false);
                assertFalse(type.isEnabled(), "Commons is still reading the selection");
                workbench.selection().setRunning(commons, false);
                assertTrue(type.isEnabled());
            } finally {
                workbench.close();
            }
        });
    }

    @Test void anOverLimitMetSelectionSaysExactlyWhyFetchIsDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject[] values = java.util.stream.IntStream.rangeClosed(1, 12)
                    .mapToObj(index -> deity("Q" + index, "Deity " + index, ""))
                    .toArray(WikidataDynamicObject[]::new);
            DiscoveryWorkbench workbench = workbench(domain(values),
                    new DiscoveryWorkbench.InitialSelection(
                            "EgyptianDeity", List.of(values), "a test"));
            try {
                RelatedRecordDiscoveryPanel met = workbench.relatedRecords().getFirst();
                JButton fetch = field(met, "fetch", JButton.class);
                JLabel availability = field(met, "availability", JLabel.class);

                assertFalse(fetch.isEnabled());
                assertEquals("Cannot fetch MuseumArtifact records from Metropolitan Museum "
                                + "of Art Collection: 12 instances are selected, but one run "
                                + "accepts at most 10. Remove 2 selected instance(s); no request "
                                + "has started.",
                        availability.getText());
            } finally {
                workbench.close();
            }
        });
    }

    /** A configured provider that is not installed leaves the other tabs working and
     *  says, where its tab would be, which file and why. */
    @Test void aDatasourceThatIsNotInstalledIsNamedWhereItsTabWouldBe() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            String problem = "Configured datasource /x/broken.datasource.json was not "
                    + "installed: declares no operations";
            DiscoveryWorkbench workbench = new DiscoveryWorkbench(domain(maat), null,
                    datasource.Datasources.standard(), List.of(problem));
            try {
                javax.swing.JTabbedPane sources = field(workbench, "sources",
                        javax.swing.JTabbedPane.class);
                int tab = sources.indexOfTab(DiscoveryWorkbench.NOT_AVAILABLE);
                assertTrue(tab >= 0);
                javax.swing.JScrollPane scroll = (javax.swing.JScrollPane)
                        sources.getComponentAt(tab);
                String text = ((javax.swing.JTextArea) scroll.getViewport().getView())
                        .getText();
                assertTrue(text.startsWith("Not installed:\n\n" + problem), text);
                assertTrue(text.contains("Correct the listed installation problem and "
                        + "restart the application"), text);
                assertTrue(text.contains("other sources are unaffected"), text);
                assertEquals(DiscoveryWorkbench.Source.PAGEVIEWS.title(),
                        sources.getTitleAt(DiscoveryWorkbench.Source.PAGEVIEWS.ordinal()));
            } finally {
                workbench.close();
            }
        });
    }

    /** The selection supplies Wikidata entities only. An operation that needs another
     *  namespace used to get a tab that failed at Fetch; it is now named as not offered. */
    @Test void anOperationNeedingAnotherNamespaceIsNotOffered() throws Exception {
        java.nio.file.Path met = java.nio.file.Path.of("..", "data", "datasources",
                "met-collection.datasource.json");
        datasource.configured.ConfiguredDatasourceDefinition definition =
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        java.nio.file.Files.readString(met).replace(
                                "\"inputNamespace\": \"wikidata\"",
                                "\"inputNamespace\": \"viaf\""),
                        datasource.configured.ConfiguredDatasourceDefinition.class);
        java.util.List<datasource.api.DatasourceProvider> providers = new java.util.ArrayList<>(
                datasource.Datasources.standard().providers());
        providers.add(new datasource.configured.ConfiguredRestDatasourceProvider(definition));
        SwingUtilities.invokeAndWait(() -> {
            DiscoveryWorkbench workbench = new DiscoveryWorkbench(
                    domain(deity("Q170471", "Maat", "Maat")), null,
                    new datasource.api.DatasourceRegistry(providers), List.of());
            try {
                assertEquals(List.of(), workbench.relatedRecords());
                javax.swing.JTabbedPane sources = field(workbench, "sources",
                        javax.swing.JTabbedPane.class);
                javax.swing.JScrollPane scroll = (javax.swing.JScrollPane)
                        sources.getComponentAt(
                                sources.indexOfTab(DiscoveryWorkbench.NOT_AVAILABLE));
                String text = ((javax.swing.JTextArea) scroll.getViewport().getView())
                        .getText();
                assertTrue(text.startsWith("Installed, but not offered by this workbench:\n\n"
                        + "Metropolitan Museum of Art Collection — "
                        + "Artifacts tagged with selected entities is not offered: it needs "
                        + "viaf entities"), text);
                assertTrue(text.contains("No file correction or restart is required."), text);
            } finally {
                workbench.close();
            }
        });
    }

    /** The checked-in configured providers; surefire runs in the app module. */
    private static DiscoveryWorkbench workbench(DomainModel model,
            DiscoveryWorkbench.InitialSelection initial) {
        var installed = datasource.configured.ConfiguredDatasourceCatalog.read(
                java.nio.file.Path.of("..", "data", "datasources"));
        assertEquals(List.of(), installed.problems());
        java.util.List<datasource.api.DatasourceProvider> providers =
                new java.util.ArrayList<>(installed.providers());
        datasource.Datasources.standard().providers().stream()
                .filter(provider -> providers.stream()
                        .noneMatch(added -> added.id().equals(provider.id())))
                .forEach(providers::add);
        return new DiscoveryWorkbench(model, initial,
                new datasource.api.DatasourceRegistry(providers), List.of());
    }

    private static WikidataDynamicObject deity(String qid, String name, String article) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, name);
        value.type("EgyptianDeity");
        value.enwikiTitle(article);
        return value;
    }

    private static DomainModel domain(WikidataDynamicObject... instances) {
        return new DomainModel() {
            @Override public List<String> types() { return List.of("EgyptianDeity"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return () -> objectview.field.FieldSet.of(instances[0]).fields();
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(instances);
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
