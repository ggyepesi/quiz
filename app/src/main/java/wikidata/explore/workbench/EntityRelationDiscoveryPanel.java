package wikidata.explore.workbench;

import wikidata.LabelledId;

import graphview.GraphViewModel;
import graphview.InteractiveGraphView;
import objectview.utils.swing.GridBagUtils;
import wikidata.explore.query.logical.DiscoverEntityRelationQuery;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.Set;
import workbench.SelectionsButton;
import workbench.WorkbenchSelections;

/** Explorer controls for bounded QID traversal through the selected property. */
final class EntityRelationDiscoveryPanel extends JPanel implements AutoCloseable {
    // The PID is typed OR handed over from Selections, and the field is the single
    // answer either way: a separate variable set only by the hand-off made a property
    // the reader already knew reachable solely by a detour through Selections.
    private final JTextField propertyPid = new JTextField(10);
    private final JLabel property = new JLabel("No property selected");
    private final JTextField startingQid = new JTextField(12);
    private final JComboBox<DiscoverEntityRelationQuery.Direction> direction =
            new JComboBox<>(DiscoverEntityRelationQuery.Direction.values());
    private final JSpinner depth = new JSpinner(new SpinnerNumberModel(3,0,12,1));
    private final JSpinner nodes = new JSpinner(new SpinnerNumberModel(250,1,5000,25));
    private final JButton discover = new JButton("Explore entity relation");
    private final JPanel selectionsHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JLabel status = new JLabel("Enter one or more starting QIDs.");
    private final InteractiveGraphView diagram = new InteractiveGraphView();
    private String propertyLabel = "";
    private boolean wired;
    private WorkbenchSelections selections;
    private Set<String> selectedNodeQids = Set.of();
    private DiscoverEntityRelationQuery.Result result;

    EntityRelationDiscoveryPanel() {
        super(new BorderLayout(6,6));
        JPanel controls = new JPanel(new GridBagLayout()); controls.setBorder(BorderFactory.createEmptyBorder(8,8,2,8));
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(3,4,3,4); c.fill = GridBagConstraints.HORIZONTAL;
        JPanel propertyRow = new JPanel(new BorderLayout(6,0));
        propertyRow.add(propertyPid, BorderLayout.WEST);
        propertyRow.add(property, BorderLayout.CENTER);
        propertyPid.setToolTipText("A PID such as P279, or use Selections to hand one over.");
        GridBagUtils.labeledRow(controls,c,0,"Edge property:",propertyRow);
        GridBagUtils.labeledRow(controls,c,1,"Starting QID:",startingQid);
        GridBagUtils.labeledRow(controls,c,2,"Direction:",direction);
        GridBagUtils.labeledRow(controls,c,3,"Maximum depth:",depth);
        GridBagUtils.labeledRow(controls,c,4,"Maximum QIDs:",nodes);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        actions.add(selectionsHolder); actions.add(discover); actions.add(status);
        GridBagUtils.wideRow(controls,5,actions);
        GridBagUtils.wideRow(controls,6,new JLabel("<html>QIDs are nodes; the selected PID is the edge. "
                + "Use several levels for hierarchical relations such as P279, or depth 1 for constraints such as P1001.</html>"));
        propertyPid.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        typed();
                    }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        typed();
                    }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        typed();
                    }
                    private void typed() {
                        // Typed over a handed-over property, so its name no longer
                        // belongs to what is in the box.
                        propertyLabel = "";
                        updateProperty();
                    }
                });
        add(controls,BorderLayout.NORTH); add(diagram,BorderLayout.CENTER); discover.setEnabled(false);
        diagram.onSelectionChanged(selected -> {
            selectedNodeQids = selected;
            if (selected.size() == 1) {
                String qid = selected.iterator().next();
                startingQid.setText(qid);
                status.setText(qid + " selected; use Selections or run from it.");
            } else {
                status.setText(selected.size()+" QID node(s) selected.");
            }
        });
    }
    void setQueryRunner(SwingQueryRunner runner) {
        if (wired || runner == null) return; wired=true;
        runner.wireButton(discover,this::accept,this::query,e -> status.setText("Discovery failed: "+message(e)));
        updateProperty();
    }

    /** The edge property as typed, or blank when it is not a PID. */
    private String pid() {
        String typed = propertyPid.getText() == null ? "" : propertyPid.getText().trim();
        return wikidata.WikidataIds.isPid(typed) ? typed.toUpperCase(java.util.Locale.ROOT) : "";
    }

    private void updateProperty() {
        String pid = pid();
        discover.setEnabled(wired && !pid.isBlank());
        // A name handed over by Selections describes the PID it came with. Once the
        // reader types a different one it describes nothing, so it stops being shown
        // rather than labelling the wrong property.
        if (pid.isBlank()) {
            propertyLabel = "";
            property.setText(propertyPid.getText() == null || propertyPid.getText().isBlank()
                    ? "No property selected" : "Not a PID");
        } else {
            property.setText(LabelledId.display(propertyLabel, pid));
        }
    }
    void selections(WorkbenchSelections value) {
        selections = value;
        selectionsHolder.removeAll();
        if (value != null) {
            selectionsHolder.add(new SelectionsButton(value).useEntities(
                    "Use selected entity as starting QID",
                    SelectionsButton.Cardinality.SINGLE,
                    selected -> useSelectedEntity(selected.getFirst())).useProperties(
                    "Use selected property as edge",
                    SelectionsButton.Cardinality.SINGLE,
                    selected -> useSelectedProperty(selected.getFirst())).addEntities(
                    "Add selected entities",
                    () -> !selectedNodeQids.isEmpty(),
                    this::setSelectedEntity).addProperties(
                    "Add selected edge property",
                    () -> !pid().isBlank(),
                    this::setSelectedProperty));
        }
        selectionsHolder.revalidate();
        selectionsHolder.repaint();
    }

    private void useSelectedEntity(WorkbenchSelections.Entity entity) {
        startingQid.setText(entity.qid());
        status.setText(entity.qid() + " is now the starting QID.");
    }

    /** The hand-off, reachable from a test in this package. */
    void useSelectedPropertyForTest(WorkbenchSelections.Property selected) {
        useSelectedProperty(selected);
    }

    private void useSelectedProperty(WorkbenchSelections.Property selected) {
        // setText fires the document listener, which drops the name because a typed
        // PID no longer has one. So the name is assigned after, not before.
        propertyPid.setText(selected.pid());
        propertyLabel = selected.label();
        updateProperty();
        status.setText(selected.pid() + " is now the edge property.");
    }
    private void setSelectedEntity() {
        if (selections == null || result == null || selectedNodeQids.isEmpty()) return;
        result.nodes().stream().filter(node -> selectedNodeQids.contains(node.qid()))
                .forEach(node -> selections.entity(node.qid(), node.label()));
    }
    private void setSelectedProperty() {
        String pid = pid();
        if (selections == null || pid.isBlank()) return;
        selections.property(pid, propertyLabel.isBlank() ? pid : propertyLabel);
    }
    private DiscoverEntityRelationQuery query() {
        String qid = startingQid.getText() == null ? "" : startingQid.getText().trim();
        return new DiscoverEntityRelationQuery(pid(),List.of(qid),(DiscoverEntityRelationQuery.Direction)direction.getSelectedItem(),
                ((Number)depth.getValue()).intValue(),((Number)nodes.getValue()).intValue());
    }
    private void accept(DiscoverEntityRelationQuery.Result r) {
        result = r;
        selectedNodeQids = Set.of();
        diagram.model(graphModel(r));
        status.setText(r.nodes().size()+" QID nodes, "+r.edges().size()+" edges"
                +(r.discoveryLimitReached()?" — discovery limit reached":""));
    }

    static GraphViewModel graphModel(DiscoverEntityRelationQuery.Result r) {
        List<GraphViewModel.Node> nodes = r.nodes().stream().map(node ->
                new GraphViewModel.Node(node.qid(), node.label(),
                        URI.create("https://www.wikidata.org/wiki/" + node.qid()),
                        node.depth(), node.depth() == 0
                                ? GraphViewModel.State.EXPANDED
                                : GraphViewModel.State.FRONTIER,
                        java.util.Map.of("Depth", Integer.toString(node.depth())), node)).toList();
        List<GraphViewModel.Edge> edges = java.util.stream.IntStream.range(0, r.edges().size())
                .mapToObj(index -> {
                    DiscoverEntityRelationQuery.Edge edge = r.edges().get(index);
                    return new GraphViewModel.Edge("relation-" + index,
                            edge.sourceQid(), edge.targetQid(), r.pid(), true);
                }).toList();
        return new GraphViewModel(nodes, edges);
    }
    private static String message(Throwable e) { return e.getMessage()==null||e.getMessage().isBlank()?e.getClass().getSimpleName():e.getMessage(); }

    /** Releases the renderer's viewer thread. The window this lives in hides rather
     *  than disposes, deliberately, so the explored graph survives a close and reopen —
     *  which means nothing here can decide when the renderer is finished. The workbench
     *  says so on the way out. */
    @Override public void close() {
        diagram.close();
    }
}
