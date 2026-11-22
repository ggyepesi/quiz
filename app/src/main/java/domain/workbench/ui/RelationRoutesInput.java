package domain.workbench.ui;

import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.RelationRoute;
import domain.datasource.persistence.RelationRouteJsonCodec;
import domain.datasource.wikidata.WikidataRouteRelation;
import wikidata.ui.WikidataLinks;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structural editor for a persisted finite relation-route list. */
final class RelationRoutesInput extends JPanel {
    private final String providerId;
    private final JLabel summary = new JLabel("No routes configured");
    private final JButton configure = new JButton("Configure routes…");
    private List<RelationRoute> routes;
    private final Runnable changed;

    RelationRoutesInput(String providerId, String value, Runnable changed) {
        super(new BorderLayout(6, 0));
        this.providerId = providerId == null ? "" : providerId.trim();
        this.changed = changed == null ? () -> { } : changed;
        this.routes = RelationRouteJsonCodec.parse(value, this.providerId);
        configure.addActionListener(event -> edit());
        add(summary, BorderLayout.CENTER);
        add(configure, BorderLayout.EAST);
        refreshSummary();
    }

    String value() { return RelationRouteJsonCodec.write(routes, providerId); }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        configure.setEnabled(enabled);
        summary.setEnabled(enabled);
    }

    private void edit() {
        RouteTableModel model = new RouteTableModel(routes);
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 24));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(
                new JComboBox<>(WikidataRouteRelation.Kind.values())));
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(
                new JComboBox<>(GraphTraversalDirection.values())));
        WikidataLinks.installOnColumn(table, 3);

        JButton addRoute = new JButton("Add route");
        JButton addStep = new JButton("Add step");
        JButton remove = new JButton("Remove step");
        addRoute.addActionListener(event -> {
            int row = model.addRoute();
            table.getSelectionModel().setSelectionInterval(row, row);
        });
        addStep.addActionListener(event -> {
            int row = model.addStep(table.getSelectedRow());
            table.getSelectionModel().setSelectionInterval(row, row);
        });
        remove.addActionListener(event -> model.remove(table.getSelectedRow()));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(addRoute);
        actions.add(addStep);
        actions.add(remove);

        JTextArea preview = new JTextArea(6, 60);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        Runnable showPreview = () -> preview.setText(preview(model));
        model.addTableModelListener(event -> showPreview.run());
        showPreview.run();
        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(actions, BorderLayout.NORTH);
        south.add(new JScrollPane(preview), BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.add(new JLabel("Each route is finite. Rows with the same route name form "
                + "one ordered path; routes are unioned."), BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Configure bounded relation routes",
                Dialog.ModalityType.APPLICATION_MODAL);
        JButton apply = new JButton("Use routes");
        JButton cancel = new JButton("Cancel");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(apply);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(content, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        cancel.addActionListener(event -> dialog.dispose());
        apply.addActionListener(event -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            try {
                List<RelationRoute> edited = model.routes();
                if (edited.isEmpty()) {
                    throw new IllegalArgumentException("Add at least one route");
                }
                routes = edited;
                refreshSummary();
                changed.run();
                dialog.dispose();
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(dialog, invalid.getMessage(),
                        "Routes not applied", JOptionPane.WARNING_MESSAGE);
            }
        });
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(820, 430);
        dialog.setMinimumSize(new Dimension(650, 300));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /** The exact routes and the requests each step makes, or why they are invalid. */
    static String preview(RouteTableModel model) {
        List<RelationRoute> edited;
        try {
            edited = model.routes();
        } catch (IllegalArgumentException invalid) {
            return "Not valid yet: " + invalid.getMessage();
        }
        if (edited.isEmpty()) return "No routes. Add at least one route.";
        StringBuilder text = new StringBuilder();
        for (RelationRoute route : edited) {
            text.append(route.label()).append('\n');
            domain.build.WikidataGraphAdjacencyAcquisition
                    .requestEstimate(route)
                    .forEach(line -> text.append("  ").append(line).append('\n'));
        }
        return text.append("Adjacency an earlier route already read is not requested "
                + "again.").toString();
    }

    private void refreshSummary() {
        int steps = routes.stream().mapToInt(route -> route.steps().size()).sum();
        summary.setText(routes.isEmpty() ? "No routes configured"
                : routes.size() + " route(s), " + steps + " explicit step(s)");
        summary.setToolTipText(routes.stream().map(route -> route.label() + ": "
                + route.steps().stream().map(step -> arrow(step.direction())
                        + step.relation().relationId())
                        .collect(java.util.stream.Collectors.joining(" → ")))
                .collect(java.util.stream.Collectors.joining("; ")));
    }

    private static String arrow(GraphTraversalDirection direction) {
        return direction == GraphTraversalDirection.INCOMING ? "incoming " : "outgoing ";
    }

    private static final class Row {
        String routeId;
        String label;
        WikidataRouteRelation.Kind kind;
        String pid;
        GraphTraversalDirection direction;

        Row(String routeId, String label, WikidataRouteRelation.Kind kind, String pid,
                GraphTraversalDirection direction) {
            this.routeId = routeId;
            this.label = label;
            this.kind = kind;
            this.pid = pid;
            this.direction = direction;
        }
    }

    static final class RouteTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Route", "Step", "Relation kind", "Property", "Direction"};
        private final List<Row> rows = new ArrayList<>();
        private int nextRoute = 1;

        RouteTableModel(List<RelationRoute> routes) {
            for (RelationRoute route : routes) {
                for (RelationRoute.Step step : route.steps()) {
                    rows.add(row(route, step));
                }
                nextRoute++;
            }
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public boolean isCellEditable(int row, int column) {
            return column != 1;
        }
        @Override public Class<?> getColumnClass(int column) {
            return column == 1 ? Integer.class
                    : column == 2 ? WikidataRouteRelation.Kind.class
                    : column == 4 ? GraphTraversalDirection.class : String.class;
        }
        @Override public Object getValueAt(int row, int column) {
            Row value = rows.get(row);
            return switch (column) {
                case 0 -> value.label;
                case 1 -> stepNumber(row);
                case 2 -> value.kind;
                case 3 -> value.pid;
                case 4 -> value.direction;
                default -> "";
            };
        }
        @Override public void setValueAt(Object value, int row, int column) {
            Row current = rows.get(row);
            switch (column) {
                case 0 -> {
                    String label = value == null ? "" : value.toString().trim();
                    for (Row candidate : rows) {
                        if (candidate.routeId.equals(current.routeId)) candidate.label = label;
                    }
                    fireTableDataChanged();
                }
                case 2 -> current.kind = (WikidataRouteRelation.Kind) value;
                case 3 -> current.pid = value == null ? "" : value.toString().trim().toUpperCase();
                case 4 -> current.direction = (GraphTraversalDirection) value;
                default -> { }
            }
            fireTableRowsUpdated(row, row);
        }

        int addRoute() {
            String id = "route-" + nextRoute++;
            while (containsRoute(id)) id = "route-" + nextRoute++;
            rows.add(new Row(id, "New route", WikidataRouteRelation.Kind.PROPERTY, "P40",
                    GraphTraversalDirection.OUTGOING));
            int index = rows.size() - 1;
            fireTableRowsInserted(index, index);
            return index;
        }

        private boolean containsRoute(String id) {
            for (Row row : rows) if (row.routeId.equals(id)) return true;
            return false;
        }

        int addStep(int selected) {
            if (selected < 0 || selected >= rows.size()) return addRoute();
            Row route = rows.get(selected);
            int at = selected + 1;
            while (at < rows.size() && rows.get(at).routeId.equals(route.routeId)) at++;
            rows.add(at, new Row(route.routeId, route.label, WikidataRouteRelation.Kind.PROPERTY, "P40",
                    GraphTraversalDirection.OUTGOING));
            fireTableRowsInserted(at, at);
            return at;
        }

        void remove(int selected) {
            if (selected < 0 || selected >= rows.size()) return;
            rows.remove(selected);
            fireTableRowsDeleted(selected, selected);
        }

        List<RelationRoute> routes() {
            LinkedHashMap<String, List<Row>> byRoute = new LinkedHashMap<>();
            rows.forEach(row -> byRoute.computeIfAbsent(row.routeId,
                    ignored -> new ArrayList<>()).add(row));
            List<RelationRoute> result = new ArrayList<>();
            for (Map.Entry<String, List<Row>> entry : byRoute.entrySet()) {
                List<Row> members = entry.getValue();
                String label = members.getFirst().label;
                if (label == null || label.isBlank()) {
                    throw new IllegalArgumentException("Every route needs a name");
                }
                List<RelationRoute.Step> steps = new ArrayList<>();
                for (Row row : members) {
                    steps.add(new RelationRoute.Step(
                            new WikidataRouteRelation(row.kind, row.pid).relation(),
                            row.direction));
                }
                result.add(new RelationRoute(entry.getKey(), label, steps));
            }
            // The same typed-walk rules the saved recipe is checked against, so the
            // dialog refuses what configuration would refuse later.
            if (!result.isEmpty()) new domain.datasource.wikidata.WikidataRelationRouteSpec(result);
            return List.copyOf(result);
        }

        private int stepNumber(int row) {
            String route = rows.get(row).routeId;
            int number = 1;
            for (int index = row - 1; index >= 0
                    && rows.get(index).routeId.equals(route); index--) number++;
            return number;
        }

        private static Row row(RelationRoute route, RelationRoute.Step step) {
            WikidataRouteRelation relation = WikidataRouteRelation.parse(step.relation());
            if (relation == null) {
                throw new IllegalArgumentException("Not a Wikidata route relation: "
                        + step.relation().relationId());
            }
            return new Row(route.id(), route.label(), relation.kind(), relation.pid(),
                    step.direction());
        }
    }
}
