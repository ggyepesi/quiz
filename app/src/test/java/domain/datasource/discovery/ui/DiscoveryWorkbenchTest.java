package domain.datasource.discovery.ui;

import domain.DomainModel;
import domain.transform.app.DomainSaver;
import domain.transform.discovery.WikidataStatementDiscoveryQuery;
import domain.transform.ui.TransformController;
import domain.workbench.execution.ui.SwingQuerySession;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import domain.runtime.WikidataDynamicObject;
import wikidata.WikidataSparqlClient;
import wikidata.query.core.WikidataAccess;
import wikidata.ui.WikidataLabels;
import work.LogNode;
import work.LogStatus;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wikipedia and Commons discovery were separate applications, each selecting its own
 * instances: moving between sources meant choosing the same instances again. One
 * workbench now holds the selection and every source action reads it, counting the selected
 * instances it cannot read rather than refusing them for everyone.
 */
class DiscoveryWorkbenchTest {

    @Test void standaloneDiscoveryInstallsLabelLookupButConstructingAPanelDoesNot()
            throws Exception {
        Field resolver = WikidataLabels.class.getDeclaredField("resolver");
        resolver.setAccessible(true);
        var before = new AtomicReference<WikidataLabels.Resolver>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                before.set((WikidataLabels.Resolver) resolver.get(null));
                WikidataLabels.resolveWith(null);
                DomainModel model = domain(deity("Q170471", "Maat", "Maat"));
                try (var panel = workbench(model, null)) {
                    assertNull(resolver.get(null), "constructing a panel must not change the resolver");
                }
                try (var windowContent = DiscoveryWorkbench.forStandaloneWindow(model, null)) {
                    assertNotNull(resolver.get(null), "standalone Discovery needs its own resolver");
                }
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            } finally {
                WikidataLabels.resolveWith(before.get());
            }
        });
    }

    @Test void statementsShareTheHostLogAndCancelTheirInFlightSparqlRequest()
            throws Exception {
        try (var server = new HeldSparqlRequest();
                var client = new WikidataSparqlClient("QuizTest", 1, server.endpoint());
                var host = new SwingQuerySession(WikidataAccess.of(client, null).bind())) {
            var workbench = mountStatements(host);
            try {
                var panel = workbench.wikidata();
                var companion = field(panel, "queries", SwingQuerySession.class);
                assertSame(host.logs(), companion.logs());
                assertNotSame(host.runner(), companion.runner());
                SwingUtilities.invokeAndWait(() -> {
                    JButton fetch = field(panel, "fetch", JButton.class);
                    assertTrue(fetch.isEnabled());
                    fetch.doClick(0);
                });
                server.accept();
                LogNode running = awaitLog(host, batch ->
                        batch.status() == LogStatus.RUNNING && batch.request().contains("Q170471"));
                assertTrue(client.isQueryRunning());
                assertFalse(host.runner().isRunning(), "the statements panel owns this run");
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(field(panel, "cancel", JButton.class).isEnabled());
                    // This is the button's callback after confirmation; do not open
                    // the modal confirmation in the headless test.
                    companion.runner().cancel();
                });
                server.assertDisconnected();
                LogNode cancelled = awaitLog(host, batch -> batch.status() == LogStatus.CANCELLED);
                assertSame(running, cancelled, "cancel finishes the batch already in Query logs");
                assertFalse(client.isQueryRunning());
            } finally {
                SwingUtilities.invokeAndWait(workbench::close);
            }
        }
    }

    @Test void closingAnIdleStatementsCompanionLeavesTheHostSparqlRequestAlone()
            throws Exception {
        try (var server = new HeldSparqlRequest();
                var client = new WikidataSparqlClient("QuizTest", 1, server.endpoint());
                var host = new SwingQuerySession(WikidataAccess.of(client, null).bind())) {
            var workbench = mountStatements(host);
            var completed = new CountDownLatch(1);
            var failure = new AtomicReference<Exception>();
            SwingUtilities.invokeAndWait(() -> host.runner().runOnCompletion(
                    new WikidataStatementDiscoveryQuery(List.of("Q170471")),
                    ignored -> completed.countDown(), error -> {
                        failure.set(error);
                        completed.countDown();
                    }, completed::countDown));
            try {
                server.accept();
                awaitLog(host, batch -> batch.status() == LogStatus.RUNNING);
                SwingUtilities.invokeAndWait(workbench::close);
                assertTrue(client.isQueryRunning(), "closing an idle companion must not cancel HTTP");
                server.respond();
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                awaitLog(host, batch -> batch.status() == LogStatus.OK);
            } finally {
                SwingUtilities.invokeAndWait(workbench::close);
            }
        }
    }

    private static DiscoveryWorkbench mountStatements(SwingQuerySession host) throws Exception {
        var mounted = new AtomicReference<DiscoveryWorkbench>();
        SwingUtilities.invokeAndWait(() -> {
            var maat = deity("Q170471", "Maat", "Maat");
            var controller = new TransformController(domain(maat), new DomainSaver());
            var workbench = new DiscoveryWorkbench(controller,
                    new domain.workbench.ui.WorkbenchActionResultNavigator(), host);
            assertTrue(workbench.selection().populate("EgyptianDeity"));
            assertEquals(0, workbench.selection().seed(List.of(maat), "Test selection"));
            mounted.set(workbench);
        });
        return mounted.get();
    }

    private static LogNode awaitLog(SwingQuerySession host, Predicate<LogNode> batchCondition)
            throws Exception {
        var batch = new AtomicReference<LogNode>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            SwingUtilities.invokeAndWait(() -> {
                for (Object value : field(host.logs(), "workflows", List.class)) {
                    LogNode run = (LogNode) value;
                    if (!run.title().equals("Fetch every Wikidata statement and qualifier")) continue;
                    for (LogNode step : run.steps()) {
                        if (!step.title().equals("Fetch statements and qualifiers in QID batches")) continue;
                        for (LogNode child : step.steps()) {
                            if (batchCondition.test(child)) batch.set(child);
                        }
                    }
                }
            });
            if (batch.get() != null) return batch.get();
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return fail("The host's Query logs never showed the expected statement batch outcome");
    }

    /** A loopback HTTP peer that sends nothing until the test releases the request.
     * Checking EOF proves the transport stopped, not just the Swing worker. */
    private static final class HeldSparqlRequest implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"));
        private Socket socket;
        private BufferedReader input;

        HeldSparqlRequest() throws Exception {
            server.setSoTimeout(5000);
        }
        String endpoint() { return "http://127.0.0.1:" + server.getLocalPort() + "/sparql"; }
        void accept() throws Exception {
            socket = server.accept();
            socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String request = input.readLine();
            assertNotNull(request);
            assertTrue(request.startsWith("GET /sparql?") && request.contains("Q170471"), request);
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) { }
            assertNotNull(line, "request headers must finish");
        }
        void assertDisconnected() throws Exception {
            assertEquals(-1, input.read(), "the client must close without a server response");
        }
        void respond() throws Exception {
            var body = "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}";
            var response = "HTTP/1.1 200 OK\r\nContent-Type: application/sparql-results+json\r\n"
                    + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        }
        @Override public void close() throws Exception {
            try {
                if (socket != null) socket.close();
            } finally {
                server.close();
            }
        }
    }

    @Test void aSelectionMadeOnceIsWhatEverySourceReads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            WikidataDynamicObject withoutArticle = deity("Q2831", "Ra", "");
            DiscoveryWorkbench workbench = workbench(domain(maat,
                    withoutArticle), new DiscoveryWorkbench.InitialSelection(
                    "EgyptianDeity", List.of(maat, withoutArticle), "a test"));
            try {
                assertEquals(2, workbench.selection().size());
                assertEquals("Fetch 1 article from 2 selected instances",
                        field(workbench.wikipedia(), "fetch", JButton.class).getText(),
                        "the Wikipedia action names both the shared selection and the "
                                + "subset with an article correspondence");
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

    @Test void sourcesActionsAndLatestResultsUseOneVisibleNavigationTree()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DiscoveryWorkbench workbench = workbench(
                    domain(deity("Q170471", "Maat", "Maat")), null);
            try {
                JTree tree = workbench.navigator().tree();
                assertEquals("workbench.action-result.tree", tree.getName());
                assertEquals(List.of("Wikipedia", "Commons", "Wikipedia pageviews",
                                "Wikidata", "Metropolitan Museum of Art Collection"),
                        children(tree, root(tree)));
                DefaultMutableTreeNode wikipedia = child(root(tree), "Wikipedia");
                assertEquals(List.of("Analyze retained articles",
                                "Fetch selected articles"),
                        children(tree, wikipedia));
                assertEquals(0, child(wikipedia, "Fetch selected articles").getChildCount(),
                        "result views appear only after the explicit action completes");
            } finally {
                workbench.close();
            }
        });
    }

    @Test void aCompletedActionPublishesItsExistingResultTabsBelowThatAction()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DiscoveryWorkbench workbench = workbench(
                    domain(deity("Q170471", "Maat", "Maat")), null);
            try {
                var result = new domain.datasource.wikipedia.discovery
                        .WikipediaStructureAnalysis.Result(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), false, List.of(), 1, 1);
                invoke(workbench.wikipedia(), "showAnalysis",
                        new Class<?>[]{result.getClass(), String.class}, result, "test");

                JTree tree = workbench.navigator().tree();
                DefaultMutableTreeNode fetch = child(
                        child(root(tree), "Wikipedia"), "Fetch selected articles");
                assertEquals("Articles — 0",
                        tree.getLastSelectedPathComponent().toString());
                assertTrue(children(tree, fetch).contains("Sections — 0"));
                javax.swing.JTabbedPane tabs = field(workbench.wikipedia(),
                        "resultTabs", javax.swing.JTabbedPane.class);
                assertEquals("Articles — 0", tabs.getTitleAt(tabs.getSelectedIndex()));
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

    /** A configured provider that is not installed leaves the other sources working and
     *  says, where its source node would be, which file and why. */
    @Test void aDatasourceThatIsNotInstalledIsNamedWhereItsTabWouldBe() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            String problem = "Configured datasource /x/broken.datasource.json was not "
                    + "installed: declares no operations";
            DiscoveryWorkbench workbench = new DiscoveryWorkbench(domain(maat), null,
                    domain.datasource.Datasources.standard(), List.of(problem));
            try {
                JTree tree = workbench.navigator().tree();
                select(tree, child(root(tree), DiscoveryWorkbench.NOT_AVAILABLE));
                String text = textArea(workbench.navigator()).getText();
                assertTrue(text.startsWith("Not installed:\n\n" + problem), text);
                assertTrue(text.contains("Correct the listed installation problem and "
                        + "restart the application"), text);
                assertTrue(text.contains("other sources are unaffected"), text);
                assertTrue(children(tree, root(tree)).contains(
                        DiscoveryWorkbench.Source.PAGEVIEWS.title()));
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
        domain.datasource.configured.ConfiguredDatasourceDefinition definition =
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        java.nio.file.Files.readString(met).replace(
                                "\"inputNamespace\": \"wikidata\"",
                                "\"inputNamespace\": \"viaf\""),
                        domain.datasource.configured.ConfiguredDatasourceDefinition.class);
        java.util.List<domain.datasource.api.DatasourceProvider> providers = new java.util.ArrayList<>(
                domain.datasource.Datasources.standard().providers());
        providers.add(new domain.datasource.configured.ConfiguredRestDatasourceProvider(definition));
        SwingUtilities.invokeAndWait(() -> {
            DiscoveryWorkbench workbench = new DiscoveryWorkbench(
                    domain(deity("Q170471", "Maat", "Maat")), null,
                    new domain.datasource.api.DatasourceRegistry(providers), List.of());
            try {
                assertEquals(List.of(), workbench.relatedRecords());
                JTree tree = workbench.navigator().tree();
                select(tree, child(root(tree), DiscoveryWorkbench.NOT_AVAILABLE));
                String text = textArea(workbench.navigator()).getText();
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
        var installed = domain.datasource.configured.ConfiguredDatasourceCatalog.read(
                java.nio.file.Path.of("..", "data", "datasources"));
        assertEquals(List.of(), installed.problems());
        java.util.List<domain.datasource.api.DatasourceProvider> providers =
                new java.util.ArrayList<>(installed.providers());
        domain.datasource.Datasources.standard().providers().stream()
                .filter(provider -> providers.stream()
                        .noneMatch(added -> added.id().equals(provider.id())))
                .forEach(providers::add);
        return new DiscoveryWorkbench(model, initial,
                new domain.datasource.api.DatasourceRegistry(providers), List.of());
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

    private static Object invoke(Object owner, String name, Class<?>[] types,
            Object... arguments) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(owner, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static DefaultMutableTreeNode root(JTree tree) {
        return (DefaultMutableTreeNode) tree.getModel().getRoot();
    }

    private static List<String> children(
            JTree tree, DefaultMutableTreeNode parent) {
        return java.util.stream.IntStream.range(0, parent.getChildCount())
                .mapToObj(parent::getChildAt)
                .map(Object::toString).toList();
    }

    private static DefaultMutableTreeNode child(
            DefaultMutableTreeNode parent, String label) {
        return java.util.stream.IntStream.range(0, parent.getChildCount())
                .mapToObj(index -> (DefaultMutableTreeNode) parent.getChildAt(index))
                .filter(node -> label.equals(node.toString())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing '" + label + "' under " + parent));
    }

    private static void select(JTree tree, DefaultMutableTreeNode node) {
        tree.setSelectionPath(new TreePath(node.getPath()));
    }

    private static javax.swing.JTextArea textArea(java.awt.Container root) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.JTextArea text) return text;
            if (component instanceof java.awt.Container child) {
                try {
                    return textArea(child);
                } catch (AssertionError ignored) { }
            }
        }
        throw new AssertionError("No text area in " + root.getClass().getName());
    }
}
