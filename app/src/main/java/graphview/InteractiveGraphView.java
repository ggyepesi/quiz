package graphview;

import objectview.utils.BrowserLauncher;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.MultiGraph;
import org.graphstream.ui.graphicGraph.GraphicElement;
import org.graphstream.ui.swing_viewer.SwingViewer;
import org.graphstream.ui.swing_viewer.ViewPanel;
import org.graphstream.ui.view.Viewer;
import org.graphstream.ui.view.util.InteractiveElement;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/** Swing-hosted open-source graph renderer with selection and neighborhood folding. */
public final class InteractiveGraphView extends JPanel implements AutoCloseable {
    private static final EnumSet<InteractiveElement> NODES =
            EnumSet.of(InteractiveElement.NODE);
    private static final String STYLESHEET = """
            graph { fill-color: #f7f8fa; padding: 70px; }
            node { shape: rounded-box; size-mode: fit; padding: 12px, 9px;
                   fill-color: #e8eef8; stroke-mode: plain; stroke-color: #8795aa;
                   stroke-width: 2px; text-color: #151a22; text-size: 18px;
                   text-alignment: center; }
            node.frontier { fill-color: #fff2cc; stroke-color: #c59a28; }
            node.expanded { fill-color: #dcefe2; stroke-color: #4e9567; }
            node.unavailable { fill-color: #f7dddd; stroke-color: #b65e5e; }
            node.selected { stroke-color: #3478c8; stroke-width: 3px; }
            node.collapsed { stroke-mode: dashes; stroke-width: 2px; }
            edge { fill-color: #43526a; size: 2px; arrow-shape: arrow;
                   arrow-size: 13px, 8px; text-color: #303b4d; text-size: 12px;
                   text-background-mode: rounded-box; text-background-color: #f7f8fa;
                   text-padding: 3px; }
            """;

    private final Graph graph = new MultiGraph("interactive");
    private final SwingViewer viewer;
    private final ViewPanel view;
    private final JLabel status = new JLabel("Run discovery to draw the graph.");
    private final JEditorPane selectedDetails = new JEditorPane("text/html", "");
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> collapsed = new LinkedHashSet<>();
    private GraphViewModel model = new GraphViewModel(List.of(), List.of());
    private Consumer<Set<String>> selectionListener = ignored -> { };
    private Consumer<String> activationListener = ignored -> { };

    public InteractiveGraphView() {
        super(new BorderLayout(4, 4));
        graph.setStrict(false);
        graph.setAutoCreate(false);
        graph.setAttribute("ui.stylesheet", STYLESHEET);
        graph.setAttribute("ui.quality");
        graph.setAttribute("ui.antialias");
        viewer = new SwingViewer(graph, Viewer.ThreadingModel.GRAPH_IN_GUI_THREAD);
        view = (ViewPanel) viewer.addDefaultView(false);
        viewer.enableAutoLayout();
        installMouseInteraction();

        JToolBar tools = new JToolBar();
        tools.setFloatable(false);
        tools.add(button("Fit", this::fit));
        tools.add(button("Collapse", this::collapseSelected));
        tools.add(button("Expand", this::expandSelected));
        tools.add(button("Open selected link", this::openSelected));
        tools.addSeparator();
        tools.add(status);
        add(tools, BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);
        selectedDetails.setEditable(false);
        selectedDetails.setOpaque(false);
        selectedDetails.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        selectedDetails.addHyperlinkListener(event -> {
            if (event.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                BrowserLauncher.open(event.getURL().toString());
            }
        });
        selectedDetails.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(selectedDetails, BorderLayout.SOUTH);
    }

    public void model(GraphViewModel value) {
        model = value == null ? new GraphViewModel(List.of(), List.of()) : value;
        selected.clear();
        collapsed.retainAll(model.nodes().stream().map(GraphViewModel.Node::id).toList());
        graph.clear();
        graph.setAttribute("ui.stylesheet", STYLESHEET);
        graph.setAttribute("ui.quality");
        graph.setAttribute("ui.antialias");
        for (GraphViewModel.Node item : model.nodes()) {
            if (item.id().isBlank()) continue;
            Node node = graph.addNode(item.id());
            node.setAttribute("ui.label", item.label() + "\n" + item.id());
            applyClass(node, item);
        }
        int ordinal = 0;
        for (GraphViewModel.Edge item : model.edges()) {
            if (graph.getNode(item.sourceId()) == null || graph.getNode(item.targetId()) == null) continue;
            String id = item.id().isBlank() ? "edge-" + ordinal++ : item.id();
            var edge = graph.addEdge(id, item.sourceId(), item.targetId(), item.directed());
            if (!item.label().isBlank()) edge.setAttribute("ui.label", item.label());
        }
        refreshVisibility();
        status.setText(model.nodes().size() + " nodes · " + model.edges().size() + " edges");
        selectedDetails.setText("");
        SwingUtilities.invokeLater(this::fit);
        selectionListener.accept(Set.of());
    }

    public void onSelectionChanged(Consumer<Set<String>> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    public void onActivated(Consumer<String> listener) {
        activationListener = listener == null ? ignored -> { } : listener;
    }

    @Override public void close() {
        viewer.disableAutoLayout();
        // GraphStream 2.0 assumes its Swing renderer has been realized before close;
        // closing a never-displayed view dereferences an uninitialized renderer map.
        if (view.isDisplayable()) viewer.close();
    }

    private void installMouseInteraction() {
        view.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                GraphicElement element = view.findGraphicElementAt(NODES, event.getX(), event.getY());
                if (element == null) return;
                String id = element.getId();
                if (SwingUtilities.isRightMouseButton(event)) {
                    toggleCollapsed(id);
                } else if (event.getClickCount() >= 2) {
                    activationListener.accept(id);
                    open(id);
                } else {
                    select(id, event.isControlDown() || event.isMetaDown() || event.isShiftDown());
                }
            }
        });
        view.addMouseWheelListener(this::zoom);
    }

    private void select(String id, boolean extend) {
        if (!extend) selected.clear();
        if (!selected.add(id)) selected.remove(id);
        refreshClasses();
        showSelectedDetails();
        status.setText(selected.size() + " selected · right-click to fold/unfold");
        selectionListener.accept(Set.copyOf(selected));
    }

    private void toggleCollapsed(String id) {
        boolean closing = collapsed.add(id);
        if (!closing) collapsed.remove(id);
        int hidden = refreshVisibility();
        status.setText(closing
                ? hidden == 0 ? "This node has no deeper discovered subgraph."
                        : hidden + " deeper node(s) hidden."
                : "Subgraph expanded; " + hidden + " node(s) remain hidden elsewhere.");
    }

    private void collapseSelected() {
        if (selected.isEmpty()) {
            status.setText("Select one or more nodes before collapsing.");
            return;
        }
        collapsed.addAll(selected);
        int hidden = refreshVisibility();
        status.setText(hidden == 0 ? "The selection has no deeper discovered nodes."
                : hidden + " deeper node(s) hidden.");
    }

    private void expandSelected() {
        if (selected.isEmpty()) {
            status.setText("Select one or more collapsed nodes before expanding.");
            return;
        }
        collapsed.removeAll(selected);
        int hidden = refreshVisibility();
        status.setText("Selection expanded; " + hidden + " node(s) remain hidden elsewhere.");
    }

    private int refreshVisibility() {
        graph.nodes().forEach(node -> node.removeAttribute("ui.hide"));
        graph.edges().forEach(edge -> edge.removeAttribute("ui.hide"));
        Set<String> hidden = new LinkedHashSet<>();
        collapsed.forEach(root -> hidden.addAll(descendants(root)));
        hidden.forEach(id -> {
            Node node = graph.getNode(id);
            if (node != null) node.setAttribute("ui.hide");
        });
        graph.edges().filter(edge -> hidden.contains(edge.getNode0().getId())
                || hidden.contains(edge.getNode1().getId()))
                .forEach(edge -> edge.setAttribute("ui.hide"));
        refreshClasses();
        return hidden.size();
    }

    private Set<String> descendants(String rootId) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        model.nodes().forEach(node -> levels.put(node.id(), node.level()));
        int rootLevel = levels.getOrDefault(rootId, Integer.MAX_VALUE);
        Set<String> hidden = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(rootId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            for (GraphViewModel.Edge edge : model.edges()) {
                String other = edge.sourceId().equals(current) ? edge.targetId()
                        : edge.targetId().equals(current) ? edge.sourceId() : null;
                if (other == null || other.equals(rootId)
                        || levels.getOrDefault(other, -1) <= rootLevel || !hidden.add(other)) continue;
                pending.add(other);
            }
        }
        return hidden;
    }

    private void refreshClasses() {
        for (GraphViewModel.Node item : model.nodes()) {
            Node node = graph.getNode(item.id());
            if (node != null) applyClass(node, item);
        }
    }

    private void applyClass(Node node, GraphViewModel.Node item) {
        List<String> classes = new ArrayList<>();
        if (item.state() != GraphViewModel.State.DEFAULT) {
            classes.add(item.state().name().toLowerCase(Locale.ROOT));
        }
        if (selected.contains(item.id())) classes.add("selected");
        if (collapsed.contains(item.id())) classes.add("collapsed");
        if (classes.isEmpty()) node.removeAttribute("ui.class");
        else node.setAttribute("ui.class", String.join(",", classes));
    }

    private void fit() {
        view.getCamera().setAutoFitView(true);
        view.getCamera().resetView();
    }

    private void zoom(MouseWheelEvent event) {
        view.getCamera().setAutoFitView(false);
        double factor = event.getWheelRotation() < 0 ? 0.85 : 1.18;
        view.getCamera().setViewPercent(view.getCamera().getViewPercent() * factor);
    }

    private void openSelected() {
        if (selected.size() == 1) open(selected.iterator().next());
        else status.setText("Select exactly one node to open its link.");
    }

    private void showSelectedDetails() {
        if (selected.size() != 1) {
            selectedDetails.setText("");
            return;
        }
        String id = selected.iterator().next();
        model.nodes().stream().filter(node -> id.equals(node.id())).findFirst().ifPresent(node -> {
            String link = node.link() == null ? escape(node.id())
                    : "<a href='" + escape(node.link().toString()) + "'>" + escape(node.id()) + "</a>";
            StringBuilder html = new StringBuilder("<html><b>").append(escape(node.label()))
                    .append("</b> · ").append(link);
            node.details().forEach((name, value) -> html.append(" &nbsp; <b>")
                    .append(escape(name)).append(":</b> ").append(escape(value)));
            selectedDetails.setText(html.append("</html>").toString());
        });
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private void open(String id) {
        model.nodes().stream().filter(node -> id.equals(node.id())).map(GraphViewModel.Node::link)
                .filter(Objects::nonNull).map(Object::toString).findFirst()
                .ifPresent(BrowserLauncher::open);
    }

    private static JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }
}
