package discovery;

import commons.discovery.CommonsDiscoveryPanel;
import domain.DomainModel;
import objectview.utils.swing.WrapLayout;
import pageviews.discovery.PageviewsDiscoveryPanel;
import quiz.transform.ui.BoundedInstanceSelection;
import quiz.transform.ui.TransformController;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikipedia.discovery.WikipediaDiscoveryPanel;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * One workbench for every discovery source. The instances are selected once, here, and
 * each source is a tab that reads exactly that selection: a new source adds a tab, not
 * an application, and moving between sources never asks for the instances again.
 *
 * <p>The selection admits instances with a Wikidata QID, the identity every source
 * starts from. A source that needs more — an English Wikipedia article — counts the
 * selected instances it cannot read and says so; each keeps its own run bound.
 */
public final class DiscoveryWorkbench extends JPanel implements AutoCloseable {
    private static final int DEFAULT_SAMPLE = 12;
    /** The largest per-run bound of any source; a source with a smaller one says so. */
    private static final int MAXIMUM = 50;

    /** The source tabs, in the order they are shown. */
    public enum Source {
        WIKIPEDIA("Wikipedia articles"),
        COMMONS("Commons depictions"),
        PAGEVIEWS("Wikipedia pageviews");

        private final String title;
        Source(String title) { this.title = title; }
        public String title() { return title; }
    }

    private final BoundedInstanceSelection selection;
    private final WikipediaDiscoveryPanel wikipedia;
    private final CommonsDiscoveryPanel commons;
    private final PageviewsDiscoveryPanel pageviews;
    private final JTabbedPane sources = new JTabbedPane();
    private final JLabel selectionStatus = new JLabel(" ");
    private boolean closed;

    public DiscoveryWorkbench(DomainModel model, InitialSelection initialSelection) {
        super(new BorderLayout(6, 6));
        TransformController domain =
                new TransformController(model, new quiz.transform.app.DomainSaver());
        selection = new BoundedInstanceSelection(domain,
                new BoundedInstanceSelection.Host() {
                    @Override public void selectionChanged(String reason) { }
                    @Override public void status(String text) {
                        selectionStatus.setText(text);
                    }
                    @Override public void updateActions() { }
                }, MAXIMUM, DEFAULT_SAMPLE, "entities",
                value -> WikidataIds.isQid(value.qid()), "have Wikidata QIDs");
        wikipedia = new WikipediaDiscoveryPanel(domain, selection);
        commons = new CommonsDiscoveryPanel(domain, selection);
        pageviews = new PageviewsDiscoveryPanel(selection);
        build();
        populate(initialSelection);
    }

    private void build() {
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        selection.addControls(controls);
        JPanel north = new JPanel(new BorderLayout(4, 3));
        north.add(controls, BorderLayout.NORTH);
        north.add(selectionStatus, BorderLayout.SOUTH);
        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        sources.addTab(Source.WIKIPEDIA.title(), wikipedia);
        sources.addTab(Source.COMMONS.title(), commons);
        sources.addTab(Source.PAGEVIEWS.title(), pageviews);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                selection.tabs(), sources);
        split.setResizeWeight(0.36);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private void populate(InitialSelection initial) {
        String requested = initial == null ? null : initial.className();
        if (!selection.populate(requested)) {
            selectionStatus.setText("This project has no class instances with Wikidata "
                    + "QIDs. No discovery source can read anything.");
            return;
        }
        if (initial == null || !initial.className().equals(selection.className())) return;
        int omitted = selection.seed(initial.instances(), "The selected instances came "
                + "from " + initial.origin() + ". Run a source to produce results.");
        selectionStatus.setText("Received exactly " + selection.size() + " selected "
                + initial.className() + " instance(s) from " + initial.origin()
                + ". Nothing has been requested."
                + (omitted == 0 ? "" : " " + omitted + " selected instance(s) had no "
                        + "Wikidata QID in this class and were not added.")
                + (selection.size() <= MAXIMUM ? "" : " Every source is disabled until "
                        + "you remove at least " + (selection.size() - MAXIMUM)
                        + " selection(s)."));
    }

    public void showSource(Source source) {
        sources.setSelectedIndex(source.ordinal());
    }

    public BoundedInstanceSelection selection() { return selection; }
    public WikipediaDiscoveryPanel wikipedia() { return wikipedia; }
    public CommonsDiscoveryPanel commons() { return commons; }
    public PageviewsDiscoveryPanel pageviews() { return pageviews; }

    /** Whether every running source may stop; keeping each is the default. */
    public boolean requestStopRun(Component owner) {
        return wikipedia.requestStopRun(owner) && commons.requestStopRun(owner)
                && pageviews.requestStopRun(owner);
    }

    /** Stops running sources, then asks about configuration this window owns. */
    public boolean requestClose(Component owner) {
        return commons.requestStopRun(owner) && pageviews.requestStopRun(owner)
                && wikipedia.requestClose(owner);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        wikipedia.close();
        commons.close();
        pageviews.close();
    }

    /** Explicit handoff from another view. The source view supplies the logical class
     *  and instances; discovery never reconstructs that selection from names. */
    public record InitialSelection(
            String className, List<WikidataDynamicObject> instances, String origin) {
        public InitialSelection {
            className = className == null ? "" : className.trim();
            instances = instances == null ? List.of()
                    : instances.stream().filter(java.util.Objects::nonNull)
                    .distinct().toList();
            origin = origin == null || origin.isBlank() ? "the originating view"
                    : origin.trim();
        }
    }

    public static JFrame openFrame(DomainModel domain, String title) {
        return openWindow(domain, title, null, null, Source.WIKIPEDIA).window();
    }

    /** One window lifecycle for the standalone navigator and contextual callers.
     *  {@code owner} is the workbench that owns the working model, or null when this
     *  window owns it. An owned window's model edits stay pending in its owner, which
     *  guards them, so closing it asks only about running work: offering to "discard"
     *  them here would name a loss that does not happen. */
    public static OpenedWindow openWindow(DomainModel domain, String title,
            InitialSelection initialSelection, Component owner, Source source) {
        JFrame frame = new JFrame("Discovery — " + title);
        DiscoveryWorkbench workbench = new DiscoveryWorkbench(domain, initialSelection);
        workbench.showSource(source);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(workbench);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                boolean close = owner == null ? workbench.requestClose(frame)
                        : workbench.requestStopRun(frame);
                if (close) frame.dispose();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                workbench.close();
            }
        });
        frame.setSize(1650, 950);
        frame.setLocationRelativeTo(owner);
        frame.setVisible(true);
        return new OpenedWindow(frame, workbench);
    }

    public record OpenedWindow(JFrame window, DiscoveryWorkbench workbench) { }
}
