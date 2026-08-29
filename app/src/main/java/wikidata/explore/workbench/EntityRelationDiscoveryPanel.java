package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.query.logical.DiscoverEntityRelationQuery;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Explorer controls for bounded QID traversal through the selected property. */
final class EntityRelationDiscoveryPanel extends JPanel {
    private final JLabel property = new JLabel("No property selected");
    private final JTextField startingQid = new JTextField(12);
    private final JComboBox<DiscoverEntityRelationQuery.Direction> direction =
            new JComboBox<>(DiscoverEntityRelationQuery.Direction.values());
    private final JSpinner depth = new JSpinner(new SpinnerNumberModel(3,0,12,1));
    private final JSpinner nodes = new JSpinner(new SpinnerNumberModel(250,1,5000,25));
    private final JButton discover = new JButton("Explore entity relation");
    private final JLabel status = new JLabel("Enter one or more starting QIDs.");
    private final EntityRelationDiagram diagram = new EntityRelationDiagram();
    private String pid = ""; private boolean wired;

    EntityRelationDiscoveryPanel() {
        super(new BorderLayout(6,6));
        JPanel controls = new JPanel(new GridBagLayout()); controls.setBorder(BorderFactory.createEmptyBorder(8,8,2,8));
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(3,4,3,4); c.fill = GridBagConstraints.HORIZONTAL;
        GridBagUtils.labeledRow(controls,c,0,"Edge property:",property);
        GridBagUtils.labeledRow(controls,c,1,"Starting QID:",startingQid);
        GridBagUtils.labeledRow(controls,c,2,"Direction:",direction);
        GridBagUtils.labeledRow(controls,c,3,"Maximum depth:",depth);
        GridBagUtils.labeledRow(controls,c,4,"Maximum QIDs:",nodes);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); actions.add(discover); actions.add(status); GridBagUtils.wideRow(controls,5,actions);
        GridBagUtils.wideRow(controls,6,new JLabel("<html>QIDs are nodes; the selected PID is the edge. "
                + "Use several levels for hierarchical relations such as P279, or depth 1 for constraints such as P1001.</html>"));
        add(controls,BorderLayout.NORTH); add(new JScrollPane(diagram),BorderLayout.CENTER); discover.setEnabled(false);
        diagram.onSelectionChanged(s -> status.setText(s.size()+" QID node(s) selected."));
        diagram.onStartingQidRequested(qid -> {
            startingQid.setText(qid);
            status.setText(qid + " is now the starting QID; press Explore to continue.");
        });
    }
    void property(WikidataPropertyViewable selected) {
        pid = selected == null ? "" : selected.pid();
        property.setText(selected == null ? "No property selected" : selected.getDisplayName()+" ("+selected.pid()+")");
        discover.setEnabled(wired && !pid.isBlank());
    }
    void setQueryRunner(SwingQueryRunner runner) {
        if (wired || runner == null) return; wired=true;
        runner.wireButton(discover,this::accept,this::query,e -> status.setText("Discovery failed: "+message(e)));
        discover.setEnabled(!pid.isBlank());
    }
    void startingQid(String qid) {
        if (wikidata.WikidataIds.isQid(qid)) startingQid.setText(qid);
    }
    private DiscoverEntityRelationQuery query() {
        String qid = startingQid.getText() == null ? "" : startingQid.getText().trim();
        return new DiscoverEntityRelationQuery(pid,List.of(qid),(DiscoverEntityRelationQuery.Direction)direction.getSelectedItem(),
                ((Number)depth.getValue()).intValue(),((Number)nodes.getValue()).intValue());
    }
    private void accept(DiscoverEntityRelationQuery.Result r) {
        diagram.result(r); status.setText(r.nodes().size()+" QID nodes, "+r.edges().size()+" edges"
                +(r.discoveryLimitReached()?" — discovery limit reached":""));
    }
    private static String message(Throwable e) { return e.getMessage()==null||e.getMessage().isBlank()?e.getClass().getSimpleName():e.getMessage(); }
}
